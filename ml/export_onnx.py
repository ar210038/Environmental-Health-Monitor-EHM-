"""Export a trained Random Forest using its recorded feature configuration."""

import argparse
from pathlib import Path

import joblib
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("--output", type=Path, default=Path("enviroguard_rf.onnx"))
    args = parser.parse_args()
    if not args.model.exists():
        raise SystemExit("Trained model file not found. Train and evaluate a real model before ONNX export.")

    artifact = joblib.load(args.model)
    if not isinstance(artifact, dict) or "model" not in artifact or "feature_columns" not in artifact:
        raise SystemExit("Model artifact lacks feature metadata; retrain with the configurable training script.")
    model = artifact["model"]
    feature_columns = artifact["feature_columns"]
    if not feature_columns:
        raise SystemExit("Model artifact contains no configured input features.")
    onnx_model = convert_sklearn(
        model,
        initial_types=[("float_input", FloatTensorType([None, len(feature_columns)]))],
    )
    args.output.write_bytes(onnx_model.SerializeToString())
    print(f"Input features ({len(feature_columns)}): {feature_columns}")
    print(f"ONNX model written to: {args.output}")


if __name__ == "__main__":
    main()
