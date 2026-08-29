"""Validate a real Android condition dataset; never generate synthetic data."""

import argparse
from pathlib import Path

import pandas as pd

RAW_FEATURE_COLUMNS = ["temperature", "humidity", "tvoc", "noise_db", "eco2"]
CANDIDATE_FEATURE_COLUMNS = ["heat_index", "time_of_day"]
TARGET_COLUMN = "condition_class"
FORBIDDEN_ERS_FEATURES = {"ers", "ersClass", "rolling_avg_ers", "dominant_factor", "legacy_ers", "legacy_ersClass", "legacy_mainContributor"}
REQUIRED_COLUMNS = [
    "timestamp", "deviceId", "temperature", "humidity",
    "tvoc", "eco2", "noise_db", "condition_class",
]


def add_engineered_features(frame: pd.DataFrame, timezone: str = "UTC") -> pd.DataFrame:
    result = frame.sort_values(["deviceId", "timestamp"]).copy()
    timestamps = pd.to_datetime(result["timestamp"], unit="ms", utc=True).dt.tz_convert(timezone)
    if result.empty:
        for column in ["heat_index", "time_of_day"]:
            result[column] = pd.Series(dtype=float)
        return result
    if "time_of_day" not in result.columns:
        result["time_of_day"] = pd.cut(
            timestamps.dt.hour,
            bins=[-1, 5, 11, 17, 23],
            labels=[0, 1, 2, 3],
        ).astype(int)
    return result


def prepare(input_path: Path, output_path: Path, timezone: str) -> None:
    frame = pd.read_csv(input_path)
    loaded = len(frame)
    missing_columns = sorted(set(REQUIRED_COLUMNS) - set(frame.columns))
    if missing_columns:
        raise ValueError(f"Missing required columns: {', '.join(missing_columns)}")
    if "collectionSessionId" not in frame.columns:
        frame["collectionSessionId"] = ""
    if "environmentLabel" not in frame.columns:
        frame["environmentLabel"] = "Unspecified"

    raw_numeric = ["timestamp", "temperature", "humidity", "tvoc", "eco2", "noise_db"]
    numeric = raw_numeric + [column for column in CANDIDATE_FEATURE_COLUMNS if column in frame.columns]
    for column in numeric:
        frame[column] = pd.to_numeric(frame[column], errors="coerce")

    valid = frame[raw_numeric].notna().all(axis=1)
    valid &= frame["timestamp"] >= 0
    valid &= frame["temperature"].between(-50, 80)
    valid &= frame["humidity"].between(0, 100)
    valid &= frame["tvoc"].between(0, 100_000)
    valid &= frame["eco2"].between(0, 100_000)
    valid &= frame["noise_db"].between(0, 200)
    cleaned = frame.loc[valid].copy()
    cleaned = add_engineered_features(cleaned, timezone)
    label_map = {"GOOD": 0, "CAUTION": 1, "HIGH RISK": 2, "HIGH_RISK": 2, "CRITICAL": 3}
    cleaned["condition_class"] = cleaned["condition_class"].astype(str).str.upper().map(label_map)
    cleaned = cleaned.dropna(subset=["condition_class"])
    cleaned["condition_class"] = cleaned["condition_class"].astype(int)
    cleaned.to_csv(output_path, index=False)

    print(f"Rows loaded: {loaded}")
    print(f"Rows removed: {loaded - len(cleaned)}")
    print(f"Rows retained: {len(cleaned)}")
    print(f"Conservative raw feature default: {RAW_FEATURE_COLUMNS}")
    print(f"Candidate engineered features (not automatically trained): {CANDIDATE_FEATURE_COLUMNS}")
    print("Target: condition_class (rule-derived operational label, not a medical prediction).")
    print(f"Prepared dataset written to: {output_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, default=Path("prepared_dataset.csv"))
    parser.add_argument(
        "--timezone",
        default="UTC",
        help="IANA timezone used only when time_of_day is absent; Android exports it directly",
    )
    args = parser.parse_args()
    if not args.input.exists():
        raise SystemExit(f"Real exported dataset not found: {args.input}")
    prepare(args.input, args.output, args.timezone)


if __name__ == "__main__":
    main()
