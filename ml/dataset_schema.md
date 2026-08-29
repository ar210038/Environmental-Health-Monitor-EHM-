# EnviroGuard dataset schema

## Raw sensor data

| Column | Meaning |
|---|---|
| `temperature` | Raw temperature in degrees Celsius. |
| `humidity` | Relative humidity in %RH. |
| `tvoc` | TVOC baseline value in ppb. |
| `eco2` | Estimated/equivalent CO2 in ppm; not a direct professional CO2 measurement. |
| `noise_db` | Estimated/relative environmental noise; not a certified sound-level measurement. |

These five fields are the conservative provisional training inputs.

## Metadata

| Column | Meaning |
|---|---|
| `timestamp` | Original Unix epoch timestamp in milliseconds. |
| `deviceId` | Source device identifier. |
| `collectionSessionId` | Optional research-session UUID. Empty means normal monitoring outside a formal session. |
| `environmentLabel` | Optional compatibility/research note, normally `Unspecified`; normal users are not expected to maintain it. |

Metadata is not a sensor feature. `collectionSessionId` should group related readings during evaluation.

## Derived environmental-condition analysis

| Column | Meaning |
|---|---|
| `heat_index` | NWS heat-index result in Celsius when its warm/humid applicability conditions are met. |
| `heat_index_status` / `tvoc_status` / `noise_status` | Independent four-level operational factor classes. |
| `condition_class` | Highest active factor severity: GOOD, CAUTION, HIGH RISK, or CRITICAL. |
| `condition_reasons` | Human-readable factor-based explanation. |
| `dominant_factor` | Occurrence-based diagnostic summary for the selected export period, not an ERS value. |

`condition_class` is a rule-derived operational label. A model trained on it reproduces documented EnviroGuard rules; it does not independently discover health-risk boundaries.

## Candidate engineered features

| Column | Meaning |
|---|---|
| `time_of_day` | 0 Night, 1 Morning, 2 Afternoon, 3 Evening. |
| `heat_index` | Candidate derived from temperature and humidity. |
| `time_of_day` | Candidate time category. |

ERS fields are retained only as `legacy_ers`, `legacy_ersClass`, and `legacy_mainContributor` for compatibility. They are prohibited ML inputs.
