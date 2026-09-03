"""
THIS MODEL IS FOR DEPLOYMENT PIPELINE TESTING ONLY.
IT IS NOT THE FINAL EHM FORECAST MODEL.
IT MUST NOT BE USED FOR RESEARCH RESULTS.

The synthetic samples created here must never be included in the final
experimental dataset. They exist only to verify Python -> ONNX -> Android.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
from sklearn.ensemble import RandomForestRegressor


RANDOM_SEED = 42
FEATURE_NAMES = (
    "temperature_celsius",
    "humidity_percent",
    "heat_index_celsius",
    "tvoc_ppb",
    "eco2_equivalent_ppm",
    "estimated_noise_level_db",
    "hour_of_day",
    "iso_day_of_week",
)
TARGET_NAMES = (
    "heat_index_celsius",
    "tvoc_ppb",
    "eco2_equivalent_ppm",
    "estimated_noise_level_db",
)
INPUT_NAME = "features"
OUTPUT_NAME = "forecast"
SCHEMA_VERSION = "smoke-test-v1"
HORIZON_MINUTES = 60
TARGET_OPSET = 17
PROJECT_ROOT = Path(__file__).resolve().parents[2]
MODEL_PATH = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "models" / "ehm_forecast_smoke_test.onnx"


def build_synthetic_training_data() -> tuple[np.ndarray, np.ndarray]:
    """Create deterministic, deliberately non-research synthetic examples."""
    rng = np.random.default_rng(RANDOM_SEED)
    rows = 96
    temperature = rng.uniform(18.0, 36.0, rows)
    humidity = rng.uniform(30.0, 85.0, rows)
    heat_index = temperature + np.maximum(temperature - 26.0, 0.0) * humidity / 120.0
    tvoc = rng.uniform(30.0, 1_500.0, rows)
    eco2 = rng.uniform(400.0, 2_500.0, rows)
    noise = rng.uniform(35.0, 95.0, rows)
    hour = rng.integers(0, 24, rows).astype(np.float32)
    iso_day = rng.integers(1, 8, rows).astype(np.float32)

    features = np.column_stack(
        (temperature, humidity, heat_index, tvoc, eco2, noise, hour, iso_day)
    ).astype(np.float32)
    targets = np.column_stack(
        (
            heat_index + 0.4,
            tvoc * 1.03 + 5.0,
            eco2 * 1.02 + 10.0,
            noise + 0.5,
        )
    ).astype(np.float32)
    return features, targets


def rename_single_output(model: onnx.ModelProto, output_name: str) -> None:
    old_name = model.graph.output[0].name
    if old_name == output_name:
        return
    for node in model.graph.node:
        for index, name in enumerate(node.output):
            if name == old_name:
                node.output[index] = output_name
    model.graph.output[0].name = output_name


def declare_multi_target_output_shape(model: onnx.ModelProto) -> None:
    """Correct skl2onnx's single-target shape hint for native multi-output RF."""
    dimensions = model.graph.output[0].type.tensor_type.shape.dim
    if len(dimensions) != 2:
        raise RuntimeError(f"Unexpected converted output rank: {len(dimensions)}")
    dimensions[1].dim_value = len(TARGET_NAMES)


def create_model() -> Path:
    features, targets = build_synthetic_training_data()
    regressor = RandomForestRegressor(
        n_estimators=12,
        max_depth=5,
        random_state=RANDOM_SEED,
        n_jobs=1,
    )
    regressor.fit(features, targets)

    model = convert_sklearn(
        regressor,
        name="EHM forecast pipeline smoke test",
        initial_types=[(INPUT_NAME, FloatTensorType([None, len(FEATURE_NAMES)]))],
        target_opset=TARGET_OPSET,
    )
    rename_single_output(model, OUTPUT_NAME)
    declare_multi_target_output_shape(model)
    model.metadata_props.add(key="ehm_model_source", value="SMOKE_TEST")
    model.metadata_props.add(key="ehm_schema_version", value=SCHEMA_VERSION)
    model.metadata_props.add(key="ehm_horizon_minutes", value=str(HORIZON_MINUTES))
    model.metadata_props.add(key="ehm_feature_order", value=",".join(FEATURE_NAMES))
    model.metadata_props.add(key="ehm_target_order", value=",".join(TARGET_NAMES))
    model.doc_string = (
        "Deployment pipeline smoke-test model only. Not trained on EHM measurements "
        "and not valid for environmental research or advice."
    )
    onnx.checker.check_model(model)

    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, MODEL_PATH)
    return MODEL_PATH


def verify_model(model_path: Path) -> None:
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    known_input = np.array(
        [[30.0, 70.0, 35.0, 300.0, 900.0, 82.0, 14.0, 3.0]],
        dtype=np.float32,
    )
    output = session.run([OUTPUT_NAME], {INPUT_NAME: known_input})[0]
    if output.shape != (1, len(TARGET_NAMES)) or not np.isfinite(output).all():
        raise RuntimeError(f"Unexpected smoke-test output: shape={output.shape}, values={output}")

    print("WARNING: smoke-test deployment model only; not a validated forecast.")
    print(f"Model: {model_path}")
    print(f"Feature order ({len(FEATURE_NAMES)}): {FEATURE_NAMES}")
    print(f"Target order ({len(TARGET_NAMES)}): {TARGET_NAMES}")
    print(f"Runtime input name/shape: {session.get_inputs()[0].name} {known_input.shape}")
    print(f"Model input shape: {session.get_inputs()[0].shape}")
    print(f"Runtime output name/shape: {session.get_outputs()[0].name} {output.shape}")
    print(f"Known inference output: {output[0].tolist()}")


if __name__ == "__main__":
    verify_model(create_model())
