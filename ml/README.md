# EHM ML workspace

This folder contains preparation code for a future one-hour Random Forest environmental forecast. It does not contain a dataset, trained model, claimed metrics, or generated ONNX file.

Current workflow:

1. Export real chronological Room readings from Settings in the Android app.
2. Review the raw schema in `dataset_schema.md` and collect enough representative history.
3. Train and evaluate only after sufficient real one-hour input/target pairs exist.
4. Compare the model with the persistence baseline printed by the training script.
5. Export to ONNX only after evaluation is acceptable.

Example commands for a future evaluation run:

```bash
python train_random_forest.py EHM_Dataset_YYYY-MM-DD.csv --model-output ehm_forecast.joblib
python export_onnx.py ehm_forecast.joblib --output ehm_forecast.onnx
```

The candidate forecast outputs are heat index, TVOC, eCO2 equivalent, and estimated noise level approximately one hour ahead. Training uses chronological splits and real observations only. Forecast output must not be described as a medical prediction or a determination of environmental safety.
