# Environmental Health Monitor (EHM)

Environmental Health Monitor is a final-year CSE IoT project for localized
indoor environmental monitoring. An ESP32 sensor unit supplies timestamped
temperature, humidity, TVOC, eCO2-equivalent, and estimated-noise readings. The
Android app turns those readings into a deterministic four-level condition,
guidance, alerts, history, and explicitly experimental one-hour forecasts.

EHM is an awareness and research prototype. It is not a medical device, does
not diagnose illness, and must not be used as a calibrated air-quality or
sound-level instrument.

## Repository status

This repository contains:

- the complete Android application and Android tests;
- Firebase Realtime Database rules and the ESP32/Firebase data contract;
- the Cloudflare Worker source and tests for optional Gemini explanations;
- reproducible Random Forest training and ONNX export scripts;
- the four deployed ONNX models, their exact feature schema, export-validation
  report, and a shared Python/Android parity fixture;
- project documentation.

The physical ESP32 uses a DHT22, SGP30, and INMP441. **The current local project
provided for this publication does not contain the ESP32 `.ino` or PlatformIO
firmware source.** Its required authentication and measurement contract is
documented in
[`docs/ESP32_FIREBASE_AUTH_CONTRACT.md`](docs/ESP32_FIREBASE_AUTH_CONTRACT.md).
No firmware has been fabricated or reconstructed for this repository.

## Architecture

```text
DHT22 + SGP30 + INMP441
          |
        ESP32  -- Espressif Security 1 SoftAP provisioning <-- Android
          |
          | authenticated HTTPS / Firebase REST
          v
Firebase Realtime Database
  /devices/{deviceId}/current          (live replacement)
  /devices/{deviceId}/history/{id}     (immutable history)
          |
          v
SensorRepository --> HomeViewModel --> deterministic condition/guidance/alerts
        |
        +--> Room v8 --> History / Trends / CSV / experimental ONNX forecast

Optional, isolated services:
  Open-Meteo --> external weather/AQI context
  Android --> Cloudflare Worker --> Gemini primary/fallback explanation
```

The app follows MVVM with Kotlin, XML layouts, ViewBinding, coroutines,
Firebase Authentication/Realtime Database, Room, MPAndroidChart, and ONNX
Runtime. Optional external context, AI explanation, and forecasting failures do
not disable the current deterministic EHM assessment.

## Hardware and measurement semantics

| Component | Measurement |
|---|---|
| ESP32 | Connectivity, sampling, provisioning, and Firebase upload |
| DHT22 | Temperature (°C) and relative humidity (%RH) |
| SGP30 | TVOC (ppb) and eCO2 (ppm equivalent) |
| INMP441 | Estimated noise level |

The SGP30 value is **eCO2 equivalent**, not direct CO2. The INMP441 output is
an **estimated, non-calibrated** noise value and must not be described as a
certified sound-pressure measurement.

## Android functionality

- Anonymous Firebase sign-in and active-device management.
- Live `/current` monitoring with missing/unavailable measurement handling.
- Firebase `/history` synchronization into nullable Room v8 records with
  device/timestamp uniqueness.
- GOOD, MODERATE, POOR, and CRITICAL deterministic assessment without a
  numerical risk score; tied primary concerns are retained.
- Deterministic environmental guidance and local Android notifications.
- Demo Mode and a clearly labelled Scenario Test that are never stored as live
  hardware data.
- SoftAP provisioning through Espressif Security 1, including device discovery,
  ESP32-side Wi-Fi scanning, and secure credential transfer.
- History metric charts (Hour/Day/Week), Trends analysis
  (Today/7 Days/30 Days), and nullable-safe CSV export.
- High-resolution PNG download for both historical chart surfaces. The app uses
  Android's Storage Access Framework (`ACTION_CREATE_DOCUMENT`), embeds the
  chart title and selected period, renders the complete fitted graph at 2×
  resolution, and requests no storage permission.
- Location-based external weather and air-quality context from Open-Meteo,
  isolated from sensor history, ML training, alerts, and Firebase.
- Optional concise Gemini explanations through the included Cloudflare Worker.

## Deterministic assessment

`EnvironmentalConditionEngine` is the authoritative current-measurement path.
It derives Heat Index with the NOAA/NWS procedure and classifies thermal, air,
and estimated-noise dimensions into four advisory levels. It has no weighted
score and no ML dependency. Missing dimensions remain unavailable rather than
being converted to zero.

Guidance is deterministic. The Gemini response, when requested, is displayed
as an optional explanation and cannot replace the computed condition or
predefined recommendations.

## Experimental one-hour forecasting

The Trends screen loads four independent Random Forest regressors from
`app/src/main/assets/models/` to estimate Heat Index, TVOC, eCO2 equivalent,
and non-calibrated estimated noise approximately one hour ahead. Inference runs
off the main thread and is labelled **Experimental forecast**. It does not feed
the current alert, assessment, or guidance paths.

The V1 models were trained from 3,032 cleaned real EHM readings (3,034 raw),
which produced 1,617 usable examples. Splitting was chronological: 70% train,
15% validation, and 15% untouched test. The preliminary models performed worse
than a persistence baseline on every held-out target:

| Target | Random Forest MAE | Persistence MAE |
|---|---:|---:|
| Heat Index (°C) | 6.777 | 4.575 |
| TVOC (ppb) | 307.929 | 249.951 |
| eCO2 (equivalent ppm) | 126.282 | 72.045 |
| Estimated noise (non-calibrated units) | 3.505 | 2.992 |

These are research results, not evidence of reliable prediction.

### Model contract and preprocessing

Every model accepts float32 tensor `X` with shape `[batch, 32]` and returns
float32 tensor `variable` with shape `[batch, 1]`. Inputs are six current
values (including derived Heat Index), the same six values near T−5, T−15, and
T−30 minutes, six current-minus-T−5 changes, and Asia/Dhaka hour sine/cosine.

Each lag must be within two minutes and in one continuous session. A gap over
three minutes starts a new session. Missing/non-finite selected measurements
make forecasting unavailable. The exact order and model version are in
[`feature_schema.json`](app/src/main/assets/models/feature_schema.json).
Python-vs-ONNX verification is in `onnx_validation.json`, and
`android_parity_fixture.json` drives cross-runtime tests.

See [`ml/README.md`](ml/README.md) for the complete training, evaluation,
10,000-record retraining, ONNX export, replacement, and parity procedure.

## Optional Gemini Worker

[`cloudflare-worker/ehm-gemini-worker.mjs`](cloudflare-worker/ehm-gemini-worker.mjs)
keeps the Gemini API key off Android. It uses `gemini-3.6-flash` first and
`gemini-3.5-flash` only for transient failures, with bounded per-model timeouts,
safe non-JSON handling, and a friendly unavailable response. Store
`GEMINI_API_KEY` as a Cloudflare Worker secret; never add it to source control.
Worker tests run with:

```bash
node --test cloudflare-worker/ehm-gemini-worker.test.mjs
```

## Project structure

```text
app/src/main/                 Android application, resources, and ONNX assets
app/src/test/                 JVM/Robolectric regression tests
app/src/androidTest/          Android ONNX parity/instrumented tests
cloudflare-worker/            Optional Gemini proxy and Node tests
docs/                         Hardware/cloud integration contracts
ml/                           Reproducible training and ONNX export pipeline
tools/ml/                     Cross-runtime parity-fixture generator
firebase-database.rules.json  Prototype authenticated database rules
```

## Android setup

Requirements:

- Android Studio with JDK 17-compatible bundled runtime;
- Android SDK 36 (minimum app SDK 26, target SDK 35);
- a Firebase Android app with Anonymous Authentication and Realtime Database;
- a physical Android device for SoftAP, notification, App Check, and final ONNX
  runtime checks.

1. Clone the repository and open its root in Android Studio.
2. Register your own Android app ID `com.enviroguard.app` in Firebase.
3. Download `google-services.json` to `app/google-services.json`. It is ignored
   and must never be committed.
4. Enable Anonymous Authentication and create Realtime Database. Review and
   deploy `firebase-database.rules.json`; the supplied authenticated-user rules
   are prototype rules, not per-device production authorization.
5. Configure Firebase App Check for debug/development and Play Integrity for
   production. Never commit App Check debug tokens.
6. Sync Gradle, then run:

```powershell
.\gradlew.bat testDebugUnitTest testReleaseUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lint
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk` and is
ignored by Git.

## Hardware integration

The Android provisioning flow expects ESP32 service names matching
`EHM_XXXXXX`. The device must write complete live replacements approximately
every 3–5 seconds and immutable history approximately every 60 seconds using
the fields `timestamp`, `temperature`, `humidity`, `tvoc`, `eco2`, and
`noiseLevel`. Timestamps are Unix epoch milliseconds.

See the checked-in hardware/Firebase contract for anonymous-auth token refresh,
secure storage, REST paths, missing-field behavior, and App Check deployment
gates. Because firmware source was not present in the supplied working tree,
firmware compilation and physical ESP32 upload are not represented as verified
repository tests.

## Testing and verification boundaries

JVM tests cover the condition engine, guidance, Room migration/nullability,
history synchronization, provisioning state, Demo/Scenario behavior, Gemini
request/fallback behavior, external context, forecasting preprocessing/parity,
and chart PNG generation/error handling.

The instrumented ONNX parity test must be run on an Android device or emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Physical-device checks are still required for actual ESP32 discovery and Wi-Fi
provisioning, Firebase/App Check deployment, notification delivery, location
permission/provider behavior, the system document picker and saved PNG visual
inspection, and sustained sensor operation.

## Security and limitations

- `google-services.json`, local SDK paths, signing keys, raw datasets, `.joblib`
  files, Python environments, build outputs, Worker secrets, and debug tokens
  are excluded from Git.
- The checked-in Firebase rules allow any authenticated prototype user to
  access any device path; production requires device/user ownership rules.
- External weather/AQI is contextual city-scale data and is not persisted as
  sensor history or used for EHM alerts/ML.
- Gemini is optional, network-dependent, and non-authoritative.
- V1 forecasts underperform persistence and require more varied real sessions
  plus a new untouched evaluation before any accuracy claim.
- eCO2 is an equivalent estimate; estimated noise is not calibrated.

## Author

Final-year Computer Science and Engineering project focused on IoT,
environmental monitoring, Android development, and applied machine learning.
