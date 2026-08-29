"""Train a leakage-safe one-hour environmental forecast only from real exported data."""
import argparse
from pathlib import Path
import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

TARGETS = ["heat_index", "tvoc", "eco2", "noise_level"]

def heat_index_c(t, rh):
    f = t * 9 / 5 + 32
    simple = .5 * (f + 61 + (f - 68) * 1.2 + rh * .094)
    return ((simple + f) / 2 - 32) * 5 / 9 if (simple + f) / 2 < 80 else (-42.379 + 2.04901523*f + 10.14333127*rh - .22475541*f*rh - .00683783*f*f - .05481717*rh*rh + .00122874*f*f*rh + .00085282*f*rh*rh - .00000199*f*f*rh*rh - 32) * 5 / 9

def build_frame(frame):
    frame = frame.sort_values("timestamp").copy()
    frame["heat_index"] = [heat_index_c(t, h) for t, h in zip(frame.temperature, frame.humidity)]
    times = pd.to_datetime(frame.timestamp, unit="ms", utc=True)
    frame["hour"] = times.dt.hour; frame["day_of_week"] = times.dt.dayofweek
    raw = ["heat_index", "tvoc", "eco2", "noise_level"]
    for column in raw:
        for minutes in (5, 15, 30):
            frame[f"{column}_mean_{minutes}m"] = frame.set_index(times)[column].rolling(f"{minutes}min", closed="left").mean().to_numpy()
        frame[f"{column}_delta"] = frame[column] - frame[column].shift(1)
    # Pair only a reading within ±5 minutes of exactly one hour ahead.
    future = frame[["timestamp"] + raw].copy(); future.timestamp -= 60 * 60 * 1000
    merged = pd.merge_asof(frame.sort_values("timestamp"), future.sort_values("timestamp"), on="timestamp", direction="nearest", tolerance=5*60*1000, suffixes=("", "_target"))
    features = [c for c in merged if c in ("hour", "day_of_week") or c.endswith(("m", "delta")) or c in raw]
    targets = [f"{c}_target" for c in raw]
    return merged.dropna(subset=features + targets), features, targets

def metrics(name, actual, predicted):
    print(name, "MAE", mean_absolute_error(actual, predicted), "RMSE", mean_squared_error(actual, predicted) ** .5, "R2", r2_score(actual, predicted))

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("dataset", type=Path); parser.add_argument("--model-output", type=Path, default=Path("ehm_forecast.joblib")); args = parser.parse_args()
    if not args.dataset.exists(): raise SystemExit("A real chronological export is required; no synthetic data is generated.")
    frame, features, targets = build_frame(pd.read_csv(args.dataset))
    if len(frame) < 20: raise SystemExit("Not enough valid real one-hour pairs for forecasting.")
    train_end, validation_end = int(len(frame)*.7), int(len(frame)*.85)
    train, test = frame.iloc[:train_end], frame.iloc[validation_end:]
    model = RandomForestRegressor(n_estimators=300, random_state=42, n_jobs=-1).fit(train[features], train[targets])
    predicted = model.predict(test[features])
    for i, target in enumerate(targets):
        metrics(target, test[target], predicted[:, i]); metrics(target + " persistence", test[target], test[target.replace("_target", "")])
    joblib.dump({"model": model, "features": features, "targets": targets}, args.model_output)

if __name__ == "__main__": main()
