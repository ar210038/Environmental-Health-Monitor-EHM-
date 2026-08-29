package com.enviroguard.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

object AuthManager {

    private val auth = FirebaseAuth.getInstance()
    private val signInMutex = Mutex()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val currentUserId: String?
        get() = auth.currentUser?.uid

    // ── SIGN IN ANONYMOUSLY ───────────────────────────────────
    suspend fun ensureAuthenticated(): Result<String> = signInMutex.withLock {
        try {
            // Reuse the current Firebase session whenever one already exists.
            auth.currentUser?.uid?.let { return@withLock Result.success(it) }

            val result = auth.signInAnonymously().await()
            val uid = result.user?.uid
                ?: return@withLock Result.failure(
                    IllegalStateException("Firebase anonymous sign-in completed without a UID")
                )

            Result.success(uid)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Firebase authentication failed. Check network access and ensure Anonymous Authentication is enabled.",
                    e
                )
            )
        }
    }

    // Retained for existing callers; all authentication now uses the same guarded path.
    suspend fun signInAnonymously(): Result<String> = ensureAuthenticated()

    fun isSignedIn(): Boolean = auth.currentUser != null
}
