package com.enviroguard.app.data.repository

import com.enviroguard.app.Constants
import com.enviroguard.app.data.local.EnviroGuardDatabase
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.model.SensorReading
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SensorRepository(private val database: EnviroGuardDatabase) {
    private val firebaseDb by lazy { FirebaseDatabase.getInstance().reference }
    private val historySynchronizer by lazy { HistoryRecordSynchronizer(RoomHistoricalReadingStore(database.sensorReadingDao())) }
    private val synchronizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val synchronizationJobs = mutableMapOf<String, Job>()
    private val synchronizationLock = Any()
    fun listenToCurrentReading(deviceId: String): Flow<SensorReading> = callbackFlow {
        val ref = firebaseDb.child("devices").child(deviceId).child("current")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { parseSensorSnapshot(snapshot)?.let { trySend(it) } }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener); awaitClose { ref.removeEventListener(listener) }
    }
    fun listenToHistory(deviceId: String): Flow<List<SensorReading>> = callbackFlow {
        val ref = firebaseDb.child("devices").child(deviceId).child("history")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.children.mapNotNull { parseSensorSnapshot(it, allowGeneratedTimestamp = false) }.sortedBy { it.timestamp })
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener); awaitClose { ref.removeEventListener(listener) }
    }
    internal fun parseSensorSnapshot(snapshot: DataSnapshot, allowGeneratedTimestamp: Boolean = true): SensorReading? = runCatching {
        if (!snapshot.exists()) return null
        val values = listOf(
            snapshot.numberAsFloat("temperature"),
            snapshot.numberAsFloat("humidity"),
            snapshot.numberAsFloat("tvoc"),
            snapshot.numberAsFloat("eco2"),
            snapshot.numberAsFloat("noiseLevel", "noiseDb")
        )
        if (values.none { it.isFinite() }) return null
        val timestamp = snapshot.child("timestamp").value?.toString()?.toLongOrNull()
            ?: snapshot.key?.toLongOrNull()
            ?: if (allowGeneratedTimestamp) System.currentTimeMillis() else return null
        SensorReading(values[0], values[1], values[2], values[3], values[4], timestamp)
    }.getOrNull()
    private fun DataSnapshot.numberAsFloat(primary: String, legacy: String? = null): Float = ((child(primary).value ?: legacy?.let { child(it).value }) as? Number)?.toFloat() ?: Float.NaN
    suspend fun saveReadingLocally(reading: SensorReading, deviceId: String, isDemo: Boolean = false) { if (!isDemo) database.sensorReadingDao().insert(createRawEntity(reading, deviceId)) }

    fun ensureHistorySynchronization(deviceId: String) {
        if (deviceId.isBlank()) return
        synchronized(synchronizationLock) {
            if (synchronizationJobs[deviceId]?.isActive == true) return
            val job = synchronizationScope.launch {
                listenToHistory(deviceId)
                    .retryWhen { _, _ -> delay(HISTORY_RETRY_DELAY_MS); true }
                    .collect { historySynchronizer.synchronize(deviceId, it) }
            }
            synchronizationJobs[deviceId] = job
            job.invokeOnCompletion {
                synchronized(synchronizationLock) {
                    if (synchronizationJobs[deviceId] === job) synchronizationJobs.remove(deviceId)
                }
            }
        }
    }
    fun getReadingsBetween(start: Long, endExclusive: Long, deviceId: String? = null): Flow<List<SensorReadingEntity>> = if (deviceId.isNullOrBlank()) database.sensorReadingDao().getReadingsBetween(start, endExclusive) else database.sensorReadingDao().getReadingsBetweenForDevice(deviceId, start, endExclusive)
    fun observeDatasetCount(): Flow<Int> = database.sensorReadingDao().observeDatasetCount()
    suspend fun getDatasetReadings(): List<SensorReadingEntity> = database.sensorReadingDao().getAllForDatasetExport()
    fun observeLatestReadingForDevice(deviceId: String): Flow<SensorReading?> =
        database.sensorReadingDao().observeLatestReadingForDevice(deviceId).map { entity ->
            entity?.let {
                it.toReading()
            }
        }
    fun getDemoReading() = SensorReading(Constants.DEMO_TEMP, Constants.DEMO_HUMIDITY, Constants.DEMO_TVOC, Constants.DEMO_ECO2, Constants.DEMO_NOISE)
    companion object {
        private const val HISTORY_RETRY_DELAY_MS = 15_000L
        internal fun createRawEntity(reading: SensorReading, deviceId: String) = SensorReadingEntity.fromReading(reading, deviceId)
    }
}
