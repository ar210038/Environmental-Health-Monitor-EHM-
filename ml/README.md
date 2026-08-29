# EnviroGuard ML workspace

This folder prepares the later real-data Random Forest workflow. It does not contain a dataset, trained model, claimed metrics, or generated ONNX file. The final feature methodology is under review.

1. Export real Room readings from Settings in the Android app.
2. Copy the CSV here or pass its path to `prepare_dataset.py`.
3. Review the retained/removed row counts and the documented label rule.
4. Train only after enough representative real sessions have been collected.
5. Export the resulting model to ONNX only after its evaluation is acceptable.

Example commands:

```bash
python prepare_dataset.py EnviroGuard_Dataset_YYYY-MM-DD.csv --output prepared_dataset.csv
python train_random_forest.py prepared_dataset.csv --split grouped --group-column collectionSessionId --model-output enviroguard_rf.joblib
python export_onnx.py enviroguard_rf.joblib --output enviroguard_rf.onnx
```

The conservative default inputs are temperature, humidity, TVOC, estimated noise, and estimated eCO2. `heat_index` and `time_of_day` are configurable candidates. ERS, ERS class, rolling ERS, and the old ERS contributor are prohibited model inputs. The target is `condition_class`.

`condition_class` is generated from EnviroGuard's documented factor-based operational rules. A Random Forest trained with this target learns to reproduce those rules from collected observations; it does not independently discover health-risk boundaries and is not a medical prediction model. It should be described as an ML-based environmental condition classifier trained on collected sensor observations and rule-derived environmental condition labels.

Adjacent five-second readings are strongly correlated. Prefer grouped evaluation using `collectionSessionId`; stratified random splitting remains only a comparison baseline.
