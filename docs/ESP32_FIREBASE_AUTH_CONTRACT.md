# ESP32 Firebase authentication contract

Chosen for the next firmware phase: Firebase Anonymous Authentication over HTTPS REST.
This document does not implement firmware, deploy rules, or contain real project credentials.

## Token lifecycle

1. With no saved refresh token, POST JSON `{"returnSecureToken":true}` to
   `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=<PROJECT_API_KEY>`.
   Retain `idToken` and the expiry derived from `expiresIn` in RAM. Persist only
   the authentication `refreshToken` in ESP32 NVS.
2. On subsequent boots, use the stored refresh token, not a new anonymous signup.
   POST form-encoded `grant_type=refresh_token&refresh_token=<REFRESH_TOKEN>` to
   `https://securetoken.googleapis.com/v1/token?key=<PROJECT_API_KEY>`.
   Read `id_token`, `expires_in`, and `refresh_token`; persist a replacement
   refresh token if returned. Keep the ID token in RAM only.
3. Refresh before expiry with a safety margin, or after a confirmed expired-token
   failure, then retry the write once. Transient network/server failures require
   bounded retry/backoff, not creation of a new account.
4. If the refresh token is definitively invalid/revoked, discard it and sign up
   anonymously again. A database permission denial alone is not proof of an
   invalid refresh token; inspect rules/App Check rather than looping signups.
5. Authenticate Realtime Database REST writes with `auth=<ID_TOKEN>`, using the
   project's actual HTTPS database URL and `.json` path suffix. The API key alone
   does not authorize database writes. Never use a legacy database secret.

Never log request URLs containing keys/tokens, token responses, Wi-Fi credentials,
or PoP. Do not embed credentials in Android code or commit them. Provisioning sends
Wi-Fi credentials only; it does not supply a Firebase session. Protect refresh-token
NVS storage in the firmware deployment; anonymous UID is not the EHM device ID.

## Unchanged measurement contract

- Stable service name/path ID: `EHM_XXXXXX`, uppercase, matching `^EHM_[A-Z0-9]{6}$`.
- `/devices/{deviceId}/current`: complete replacement approximately every 3–5 seconds.
- `/devices/{deviceId}/history/{recordId}`: immutable observation approximately every 60 seconds.
- Raw fields: `timestamp`, `temperature`, `humidity`, `tvoc`, `eco2`, `noiseLevel`.
- Timestamp: positive integer Unix epoch milliseconds. Device ID is path-only.
- Units: Celsius, %RH, ppb TVOC, ppm CO2-equivalent, estimated environmental dB.
- Missing sensor fields: JSON null or omitted from a complete replacement; never
  failure zeroes or NaN. For PATCH, explicitly use null to remove stale values.
- Do not upload Android assessments, guidance, ONNX forecasts, or Gemini output.

## Deployment gates (not changed by this Android pass)

Enable Anonymous Auth and verify the deployed rules/database URL before hardware
testing. The checked-in rules allow any authenticated user to read/write any device;
they provide no per-device ownership. This prototype limitation is not production
authorization. Verify Realtime Database App Check enforcement separately: Android
debug/Play Integrity providers do not automatically authorize an ESP32 REST client.
Do not disable enforcement or weaken rules implicitly to get an upload working.

References: [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth)
and [Realtime Database REST authentication](https://firebase.google.com/docs/database/rest/auth).
