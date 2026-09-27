"""Train reproducible, session-aware 60-minute EHM forecasts from real device CSV.

Run:
    python train_ehm.py --input data/EHM_Dataset_2026-09-27.csv --output output

The test set is used ONCE, after model depth is chosen on validation data.
This script never uses a future measurement as a model input.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path

import joblib
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

SENSORS = ['temperature', 'humidity', 'tvoc', 'eco2', 'noise_level']
TARGETS = ['heat_index', 'tvoc', 'eco2', 'noise_level']
EXPECTED_COLS = ['timestamp', 'deviceId'] + SENSORS
LAGS_MINUTES = [5, 15, 30]
SESSION_GAP_MINS = 3  # A gap >3min splits collection sessions.
LAG_TOLERANCE_MINS = 2
TARGET_TOLERANCE_MINS = 2
RANDOM_SEED = 42


def heat_index_celsius(temp_c, rh_percent):
    """NOAA/NWS heat-index algorithm, including the simpler cool-weather expression.

    Verify this matches Android's existing heat-index calculation before deployment.
    Inputs and output may be scalar or NumPy arrays.
    """
    t = np.asarray(temp_c, dtype=float)
    h = np.asarray(rh_percent, dtype=float)
    f = t * 9.0 / 5.0 + 32.0
    simple = 0.5 * (f + 61.0 + (f - 68.0) * 1.2 + h * 0.094)
    avg = (simple + f) / 2.0
    rothfusz = (-42.379 + 2.04901523 * f + 10.14333127 * h
                - 0.22475541 * f * h - 0.00683783 * f * f
                - 0.05481717 * h * h + 0.00122874 * f * f * h
                + 0.00085282 * f * h * h
                - 0.00000199 * f * f * h * h)
    low_adj = ((13 - h) / 4.0) * np.sqrt(np.maximum(0, (17 - np.abs(f - 95)) / 17.0))
    high_adj = ((h - 85.0) / 10.0) * ((87.0 - f) / 5.0)
    rothfusz = np.where((h < 13) & (f >= 80) & (f <= 112), rothfusz - low_adj, rothfusz)
    rothfusz = np.where((h > 85) & (f >= 80) & (f <= 87), rothfusz + high_adj, rothfusz)
    result_f = np.where(avg >= 80, rothfusz, simple)
    return (result_f - 32.0) * 5.0 / 9.0


def load_clean_csv(path, min_records=200):
    raw = pd.read_csv(path)
    missing = sorted(set(EXPECTED_COLS) - set(raw.columns))
    if missing:
        raise ValueError('Missing CSV columns: ' + ', '.join(missing))
    if len(raw) < min_records:
        raise ValueError(f'Need at least {min_records} sensor records.')
    df = raw[EXPECTED_COLS].copy()
    for name in ['timestamp'] + SENSORS:
        df[name] = pd.to_numeric(df[name], errors='coerce')
    df['deviceId'] = df['deviceId'].astype(str)
    n_initial = len(df)
    df = df.dropna(subset=EXPECTED_COLS).drop_duplicates(['deviceId', 'timestamp'])
    df = df.sort_values(['deviceId', 'timestamp']).reset_index(drop=True)
    plausible = (df['timestamp'] > 1_500_000_000_000) & df['temperature'].between(-10, 60) & df['humidity'].between(0, 100) & df['tvoc'].between(0, 60000) & df['eco2'].between(0, 60000) & df['noise_level'].between(0, 200)
    df = df.loc[plausible].copy().reset_index(drop=True)
    # Only remove an ISOLATED, simultaneous temperature+humidity reset,
    # not persistent environmental changes or the TVOC peaks.
    gp = df.groupby('deviceId', sort=False)
    pt, nt = gp.temperature.shift(1), gp.temperature.shift(-1)
    ph, nh = gp.humidity.shift(1), gp.humidity.shift(-1)
    prev_time, next_time = gp.timestamp.shift(1), gp.timestamp.shift(-1)
    near = (df.timestamp-prev_time < 180_000) & (next_time-df.timestamp < 180_000)
    isolated = (near & ((df.temperature-pt).abs() > 8) & ((df.temperature-nt).abs() > 8)
                & ((pt-nt).abs() < 2) & ((df.humidity-ph).abs() > 20)
                & ((df.humidity-nh).abs() > 20) & ((ph-nh).abs() < 10))
    glitches = df.loc[isolated, ['timestamp', 'deviceId', 'temperature', 'humidity']].copy()
    df = df.loc[~isolated].copy().reset_index(drop=True)
    df['heat_index'] = heat_index_celsius(df.temperature.to_numpy(), df.humidity.to_numpy())
    df['gap_ms'] = df.groupby('deviceId').timestamp.diff()
    df['session'] = df.groupby('deviceId')['gap_ms'].transform(lambda s: (s.fillna(10**10)>SESSION_GAP_MINS * 60_000).cumsum()).astype(int)
    report = {'original_records':n_initial,'clean_records':len(df), 'removed_missing_duplicates_invalid_or_glitches':n_initial-len(df), 'isolated_temperature_humidity_glitches':len(glitches),
              'device_ids':sorted(df.deviceId.unique().tolist()),'session_count':int(df.groupby(['deviceId','session']).ngroups),
              'tvoc_over_2200_retained':int((df.tvoc>2200).sum()), 'eco2_at_400':int((df.eco2==400).sum()),
              'sampling_interval_sec_median':float(df.loc[df.gap_ms.between(0,180_000), 'gap_ms'].median()/1000)}
    return df, glitches, report


def build_features(df):
    """Build exactly the same past-only features for training or live prediction."""
    df = df.sort_values(['timestamp','deviceId','session']).reset_index(drop=True)
    readings = SENSORS + ['heat_index']
    base = df[['timestamp','deviceId','session']+readings].copy()
    base['local_hour'] = (pd.to_datetime(base.timestamp,unit='ms',utc=True)
                          .dt.tz_convert('Asia/Dhaka').dt.hour
                          + pd.to_datetime(base.timestamp,unit='ms',utc=True)
                          .dt.tz_convert('Asia/Dhaka').dt.minute / 60)
    base['hour_sin'] = np.sin(2*np.pi*base.local_hour/24)
    base['hour_cos'] = np.cos(2*np.pi*base.local_hour/24)
    feature_names = readings.copy()
    matching = df[['timestamp','deviceId','session']+readings].copy()
    matching = matching.sort_values('timestamp')
    for lag in LAGS_MINUTES:
        tcol=f'lookup_{lag}m'
        base[tcol]=base.timestamp-lag*60_000
        renamed={c:f'{c}_lag{lag}m' for c in readings}
        other=matching.rename(columns={'timestamp':f'lag_timestamp_{lag}m',**renamed})
        base=pd.merge_asof(base.sort_values(tcol), other.sort_values(f'lag_timestamp_{lag}m'),
                           left_on=tcol, right_on=f'lag_timestamp_{lag}m',
                           by=['deviceId','session'], direction='nearest', tolerance=LAG_TOLERANCE_MINS*60_000)
        feature_names.extend(renamed.values())
        base=base.drop(columns=[tcol,f'lag_timestamp_{lag}m'])
    # Five-minute slope supplies useful trend information to the model.
    for r in readings:
        col=f'{r}_change5m';base[col]=base[r]-base[f'{r}_lag5m']; feature_names.append(col)
    feature_names.extend(['hour_sin','hour_cos'])
    return base, feature_names


def build_examples(df, horizon_minutes=60):
    """Match past-only inputs to measurements approximately one hour ahead."""
    base, feature_names = build_features(df)
    base['future_lookup']=base.timestamp+horizon_minutes*60_000
    future=df[['timestamp','deviceId','session']+TARGETS].rename(columns={'timestamp':'target_timestamp',**{t:'target_'+t for t in TARGETS}})
    base=pd.merge_asof(base.sort_values('future_lookup'), future.sort_values('target_timestamp'),
                       left_on='future_lookup',right_on='target_timestamp',by=['deviceId','session'],
                       direction='nearest',tolerance=TARGET_TOLERANCE_MINS*60_000)
    base=base.dropna(subset=feature_names+['target_'+t for t in TARGETS]+['target_timestamp']).copy()
    base['actual_horizon_min']=(base.target_timestamp-base.timestamp)/60_000
    base=base.sort_values(['target_timestamp','timestamp']).reset_index(drop=True)
    return base, feature_names


def scores(actual,prediction):
    return {'MAE':float(mean_absolute_error(actual,prediction)),
            'RMSE':float(np.sqrt(mean_squared_error(actual,prediction))),
            'R2':float(r2_score(actual,prediction))}


def draw_chart(table, name, path):
    """Never draw a fake continuous line across overnight recording gaps."""
    plt.figure(figsize=(12,4))
    ts = pd.to_datetime(table.target_timestamp,unit='ms',utc=True).dt.tz_convert('Asia/Dhaka')
    groups = (table.target_timestamp.diff().fillna(0)>3*60_000).cumsum()
    for first,segment in enumerate((table.loc[groups==g] for g in groups.unique())):
        xt=ts.loc[segment.index]
        plt.plot(xt,segment['actual_'+name],label='Actual' if first==0 else None,
                 linewidth=1.8,color='#155fa0')
        plt.plot(xt,segment['rf_'+name],label='Random Forest' if first==0 else None,
                 linewidth=1.4,alpha=.9,color='#f08022')
        plt.plot(xt,segment['baseline_'+name],label='Persistence baseline' if first==0 else None,
                 linewidth=1,alpha=.7,color='#1c9955')
    plt.ylabel({'heat_index':'Heat index (°C)','tvoc':'TVOC (ppb)', 'eco2':'eCO₂ (ppm)', 'noise_level':'Noise (estimated units)'}[name])
    plt.title(name.replace('_',' ').title()+' — unseen chronological test period')
    plt.legend(loc='best');plt.grid(alpha=.25);plt.tight_layout();plt.savefig(path,dpi=160);plt.close()


def main():
    parser=argparse.ArgumentParser(description='EHM reproducible 1-hour sensor forecasting training')
    parser.add_argument('--input',default='data/EHM_Dataset_2026-09-27.csv',help='EHM app exported CSV')
    parser.add_argument('--output',default='output',help='Output directory')
    parser.add_argument('--horizon',type=int,default=60,help='Forecast horizon in minutes; use 60 for thesis')
    args=parser.parse_args()
    output=Path(args.output); output.mkdir(parents=True,exist_ok=True)
    df,glitches,report=load_clean_csv(args.input)
    glitches.to_csv(output/'flagged_glitches.csv',index=False)
    ex,features=build_examples(df,args.horizon)
    if len(ex)<300:
        raise ValueError(f'Only {len(ex)} valid continuous-history one-hour examples. More recording sessions needed.')
    n=len(ex);train_end=int(n*.70);val_end=int(n*.85)
    train,val,test=ex.iloc[:train_end],ex.iloc[train_end:val_end],ex.iloc[val_end:]
    Xtrain=train[features].to_numpy(dtype=np.float32)
    Xval=val[features].to_numpy(dtype=np.float32)
    Xdev=ex.iloc[:val_end][features].to_numpy(dtype=np.float32)
    Xtest=test[features].to_numpy(dtype=np.float32)
    if not np.isfinite(Xdev).all() or not np.isfinite(Xtest).all():raise ValueError('Non-finite features detected')
    report.update({'forecast_horizon_min':args.horizon, 'valid_examples':n, 'feature_count':len(features),
        'split_method':'chronological by FUTURE TARGET timestamp: 70% train, 15% validation, 15% untouched test',
        'train_examples':len(train),'validation_examples':len(val),'test_examples':len(test),
        'first_bd':str(pd.to_datetime(df.timestamp.min(),unit='ms',utc=True).tz_convert('Asia/Dhaka')),
        'last_bd':str(pd.to_datetime(df.timestamp.max(),unit='ms',utc=True).tz_convert('Asia/Dhaka')),
        'first_test_target_bd':str(pd.to_datetime(test.target_timestamp.min(),unit='ms',utc=True).tz_convert('Asia/Dhaka')),
        'last_test_target_bd':str(pd.to_datetime(test.target_timestamp.max(),unit='ms',utc=True).tz_convert('Asia/Dhaka')),
        'test_actual_horizon_min_p05':float(test.actual_horizon_min.quantile(.05)),
        'test_actual_horizon_min_p95':float(test.actual_horizon_min.quantile(.95))})
    metrics=[]
    pred=test[['timestamp','target_timestamp','deviceId','actual_horizon_min']].copy()
    choices={}
    for target in TARGETS:
        ytrain=train['target_'+target].to_numpy()
        yval=val['target_'+target].to_numpy()
        ydev=ex.iloc[:val_end]['target_'+target].to_numpy()
        ytest=test['target_'+target].to_numpy()
        # Small, reproducible validation-only hyperparameter selection.
        best=None
        for depth in [8,14]:
            model=RandomForestRegressor(n_estimators=120,max_depth=depth,min_samples_leaf=4,
                                        random_state=RANDOM_SEED,n_jobs=-1)
            model.fit(Xtrain,ytrain)
            vpred=model.predict(Xval)
            vmse=mean_squared_error(yval,vpred)
            if best is None or vmse<best['val_mse']:
                best={'depth':depth,'val_mse':vmse,'val_MAE':float(mean_absolute_error(yval,vpred))}
        chosen_depth=best['depth'];choices[target]=best
        # Only AFTER validation choose depth, refit on train+validation. Test remains untouched.
        final=RandomForestRegressor(n_estimators=120,max_depth=chosen_depth,min_samples_leaf=4,
                                    random_state=RANDOM_SEED,n_jobs=-1)
        final.fit(Xdev,ydev)
        rf=final.predict(Xtest)
        persistence=test[target].to_numpy()
        s_rf=scores(ytest,rf);s_p=scores(ytest,persistence)
        for method,sc in [('Random Forest',s_rf),('Persistence (current = +1h)',s_p)]:
            metrics.append({'target':target,'method':method,**sc,'test_examples':len(test),'unit':{'heat_index':'°C','tvoc':'ppb','eco2':'ppm (equivalent)','noise_level':'estimated units'}[target]})
        pred['actual_'+target]=ytest
        pred['rf_'+target]=rf
        pred['baseline_'+target]=persistence
        joblib.dump(final,output/f'rf_{target}.joblib',compress=3)
        draw_chart(pred,target,output/f'forecast_{target}.png')
    pred.to_csv(output/'test_predictions.csv',index=False)
    pd.DataFrame(metrics).to_csv(output/'metrics.csv',index=False,float_format='%.4f')
    # Deployment schema is part of the model: same order, same features in Kotlin.
    schema={'model_version':f'EHM_RF_H{args.horizon}_{len(df)}cleanrecords','input_dtype':'float32',
            'one_hour_horizon_minutes':args.horizon,'sensor_columns':SENSORS,
            'targets':TARGETS,'feature_order':features,'lag_minutes':LAGS_MINUTES,
            'lag_matching_tolerance_minutes':LAG_TOLERANCE_MINS,
            'max_allowed_gap_between_sensor_samples_minutes':SESSION_GAP_MINS,
            'target_matching_tolerance_minutes':TARGET_TOLERANCE_MINS,
            'time_zone_for_hour_features':'Asia/Dhaka',
            'heat_index_formula':'NOAA/NWS: simple expression, switch to Rothfusz when average >= 80F; low/high RH adjustments. Must match Android implementation.',
            'model_files':{t:f'rf_{t}.joblib' for t in TARGETS},
            'onnx_files':{t:f'rf_{t}.onnx' for t in TARGETS},
            'validation_selected_max_depth':{t:choices[t]['depth'] for t in TARGETS}}
    (output/'feature_schema.json').write_text(json.dumps(schema,indent=2),encoding='utf-8')
    (output/'data_report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    (output/'validation_choices.json').write_text(json.dumps(choices,indent=2),encoding='utf-8')
    print('DATA',json.dumps(report,indent=2));print('\nSELECTED DEPTHS',schema['validation_selected_max_depth'])
    print('\nUNSEEN TEST METRICS (lower MAE/RMSE is better; higher R2 is better):')
    print(pd.DataFrame(metrics)[['target','method','MAE','RMSE','R2','test_examples']].round(3).to_string(index=False))
    print('\nSaved models, metrics, test predictions, graphs and feature schema to',output.resolve())

if __name__=='__main__': main()
