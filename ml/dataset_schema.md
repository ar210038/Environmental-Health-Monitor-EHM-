# EHM raw dataset schema

The Android app exports chronological, unlabelled sensor observations. It does not export derived condition classes, scores, predictions, or research-session metadata.

| Column | Meaning |
|---|---|
| `timestamp` | Unix epoch timestamp in milliseconds. |
| `deviceId` | Source monitoring-device identifier. |
| `temperature` | Temperature in degrees Celsius. |
| `humidity` | Relative humidity in %RH. |
| `tvoc` | SGP30 total volatile organic compound indicator in ppb. |
| `eco2` | SGP30 CO2-equivalent signal in ppm; not direct CO2 measurement. |
| `noise_level` | Estimated INMP441 noise level; not a calibrated sound-level measurement. |

These raw observations are the source data for the planned one-hour forecasting workflow. Heat index and temporal rolling features may be calculated during model preparation. The Android deterministic condition engine remains separate from ML forecasting.
