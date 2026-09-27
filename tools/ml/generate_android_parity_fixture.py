"""Create a real-history Android parity fixture from a verified EHM model run."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import joblib
import numpy as np
import onnxruntime as ort


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--training-root", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    training_root = args.training_root.resolve()
    dataset = args.dataset.resolve()
    model_dir = args.model_dir.resolve()
    sys.path.insert(0, str(training_root))
    from train_ehm import build_features, load_clean_csv  # noqa: PLC0415

    schema = json.loads((model_dir / "feature_schema.json").read_text(encoding="utf-8"))
    frame, _, _ = load_clean_csv(dataset)
    feature_frame, feature_order = build_features(frame)
    if feature_order != schema["feature_order"]:
        raise RuntimeError("Feature schema mismatch; do not create an Android fixture.")
    valid = feature_frame.dropna(subset=feature_order)
    selected = valid.iloc[-1]
    features = selected[feature_order].to_numpy(dtype=np.float32).reshape(1, -1)
    anchor = int(selected.timestamp)
    device_id = str(selected.deviceId)
    session_id = int(selected.session)
    history = frame.loc[
        (frame.deviceId == device_id)
        & (frame.session == session_id)
        & (frame.timestamp >= anchor - 32 * 60_000)
        & (frame.timestamp <= anchor)
    ]

    predictions = {}
    for target in schema["targets"]:
        sklearn_model = joblib.load(model_dir / schema["model_files"][target])
        sklearn_value = float(sklearn_model.predict(features)[0])
        session = ort.InferenceSession(
            str(model_dir / schema["onnx_files"][target]),
            providers=["CPUExecutionProvider"],
        )
        onnx_value = float(np.asarray(session.run(None, {session.get_inputs()[0].name: features})[0]).reshape(-1)[0])
        predictions[target] = {
            "sklearn": sklearn_value,
            "onnx": onnx_value,
            "absolute_difference": abs(sklearn_value - onnx_value),
        }

    records = history[["timestamp", "deviceId", "temperature", "humidity", "tvoc", "eco2", "noise_level"]]
    payload = {
        "model_version": schema["model_version"],
        "dataset_sha256": hashlib.sha256(dataset.read_bytes()).hexdigest(),
        "anchor_timestamp": anchor,
        "feature_order": feature_order,
        "features_float32": [float(value) for value in features[0]],
        "history": records.to_dict(orient="records"),
        "predictions": predictions,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"Wrote Android parity fixture: {args.output.resolve()}")


if __name__ == "__main__":
    main()
