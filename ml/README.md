# EHM experimental forecasting workspace

This directory contains the reproducible Python pipeline copied from the
verified `EHM_ML_V1_Consistent` kit. It is the source of truth for data
cleaning, feature order, chronological evaluation, persistence-baseline
comparison, model training, and ONNX export.

The current V1 Random Forest models are research artifacts. All four performed
worse than persistence on the held-out test data, so Android labels their
outputs **Experimental forecast** and keeps them separate from the live
deterministic assessment, guidance, and alerts.

## Files

- `train_ehm.py`: trains four regressors and writes models, metrics, graphs,
  data reports, and `feature_schema.json`.
- `predict_latest.py`: runs the Python models against the latest complete
  history window.
- `export_onnx.py`: exports all four models and compares ONNX Runtime with
  scikit-learn on up to 64 real examples.
- `test_training.py`: checks cleaning, feature completeness, horizon matching,
  session isolation, and Heat Index behavior.
- `train_random_forest.py`: compatibility alias for `train_ehm.py`; it does not
  contain a second feature pipeline.
- `dataset_schema.md`: Android CSV column contract.

The raw training CSV and `.joblib` files are deliberately not stored here.
Keep datasets and model-build outputs outside source control. The V1 Python
regression test expects the kit's unchanged CSV at
`ml/data/EHM_Dataset_2026-09-27.csv`; copy it there locally before running that
test, but do not commit it.

## Current Android contract

Each model consumes float32 tensor `X` with shape `[batch, 32]` and returns
float32 tensor `variable` with shape `[batch, 1]`. The feature order is the
order in the matching `feature_schema.json`; it must never be inferred from a
map or reordered alphabetically.

The 32 inputs are six current values (temperature, humidity, TVOC, eCO2,
estimated noise, and derived Heat Index), those same six values at 5, 15, and
30 minute lags, six current-minus-5-minute changes, and Asia/Dhaka hour sine
and cosine. Lag matches must be within two minutes and must remain in one
session; a sensor gap longer than three minutes starts a new session.

## Replacing models after collecting 10,000 records

From the project root in PowerShell, using Python 3.11:

```powershell
py -3.11 -m venv ml\.venv
ml\.venv\Scripts\python.exe -m pip install -r ml\requirements_onnx.txt
# With the original V1 CSV copied into ml\data:
ml\.venv\Scripts\python.exe -m unittest discover -s ml -p test_training.py
ml\.venv\Scripts\python.exe ml\train_ehm.py --input <export.csv> --output <model-output>
ml\.venv\Scripts\python.exe ml\export_onnx.py --model-dir <model-output> --input <export.csv>
```

Do not deploy if `onnx_validation.json` is absent or any parity check fails.
Review `metrics.csv` against persistence before copying models; more rows alone
do not prove that forecasting improved.

Generate the shared Python/Kotlin parity fixture:

```powershell
ml\.venv\Scripts\python.exe tools\ml\generate_android_parity_fixture.py `
  --training-root ml `
  --dataset <export.csv> `
  --model-dir <model-output> `
  --output <model-output>\android_parity_fixture.json
```

Only as one matched set, replace these files under
`app/src/main/assets/models/`:

- `rf_heat_index.onnx`
- `rf_tvoc.onnx`
- `rf_eco2.onnx`
- `rf_noise_level.onnx`
- `feature_schema.json`
- `onnx_validation.json`
- `android_parity_fixture.json`

Then update `ForecastModelConfig.MODEL_VERSION` to the new schema version, run
the Python tests, Android JVM tests, compile the instrumented test, and run the
instrumented ONNX parity test on a physical/emulated Android device. Do not
feed forecast values into the real-time condition engine or alert system.
