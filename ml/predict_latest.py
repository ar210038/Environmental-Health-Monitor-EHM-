"""Show a provisional ~one-hour prediction from the last available reading.

Usage: python predict_latest.py --input data/YOUR_EXPORT.csv --model-dir output
The trained models must already exist. Model outputs are NOT safety decisions.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import joblib
import numpy as np
import pandas as pd
from train_ehm import load_clean_csv,build_features


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--input',required=True)
    ap.add_argument('--model-dir',default='output')
    args=ap.parse_args()
    mdir=Path(args.model_dir)
    schema=json.loads((mdir/'feature_schema.json').read_text(encoding='utf-8'))
    df,glitches,report=load_clean_csv(args.input,min_records=31)
    last_device= df.loc[df.timestamp.idxmax(),'deviceId']
    most_recent=df[df.deviceId==last_device].timestamp.max()
    base,actual_order=build_features(df)
    expected_order=schema['feature_order']
    if actual_order!=expected_order:
        raise ValueError('Feature order differs from model. DO NOT forecast. Check code version and feature_schema.json.')
    last=base.loc[(base.deviceId==last_device)&(base.timestamp==most_recent)]
    if last.empty or last[expected_order].isna().any(axis=None):
        raise RuntimeError('Need at least 30 minutes of uninterrupted sensor history for the newest reading. Keep recording and try again.')
    X=last[expected_order].to_numpy(dtype=np.float32)
    t=pd.to_datetime(most_recent,unit='ms',utc=True).tz_convert('Asia/Dhaka')
    print('Device:',last_device,'\nLatest sample:',t,'\nForecast for:',t+pd.Timedelta(minutes=schema['one_hour_horizon_minutes']))
    print('---- CURRENT => FORECAST (UNVERIFIED future reading) ----')
    for tgt in schema['targets']:
        model=joblib.load(mdir/schema['model_files'][tgt])
        value=float(model.predict(X)[0]);current=float(last[tgt].iloc[0])
        unit={'heat_index':'°C','tvoc':'ppb','eco2':'ppm (equivalent)','noise_level':'estimated units'}[tgt]
        print(f'{tgt:13s}: {current:8.2f} -> {value:8.2f} {unit}')
    print('Note: This pilot Random Forest did not beat persistence on the Sept 27 test period.')

if __name__=='__main__':main()
