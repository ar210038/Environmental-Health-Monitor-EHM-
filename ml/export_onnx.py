"""Convert previously trained EHM Random Forest models to ONNX and verify outputs.

On a Windows PC with internet:
    python -m pip install -r requirements_onnx.txt
    python export_onnx.py --model-dir output --input data/EHM_Dataset_2026-09-27.csv

Use the same source data CSV and the feature_schema.json saved during training.
"""
import argparse
import json
from pathlib import Path
import joblib
import numpy as np
from train_ehm import load_clean_csv, build_features


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--model-dir',default='output')
    parser.add_argument('--input',required=True,help='Same raw CSV used for training')
    args=parser.parse_args()
    folder=Path(args.model_dir)
    schema=json.loads((folder/'feature_schema.json').read_text(encoding='utf-8'))
    try:
        from skl2onnx import to_onnx
        import onnx
        import onnxruntime as ort
    except ImportError as ex:
        raise SystemExit('ONNX packages missing. Run: python -m pip install -r requirements_onnx.txt') from ex
    df,_,_=load_clean_csv(args.input)
    examples,feat_order=build_features(df)
    if feat_order != schema['feature_order']:
        raise ValueError('Model feature schema differs from current script. STOP: retrain models or restore original code.')
    valid=examples.dropna(subset=feat_order)
    if valid.empty:raise ValueError('No valid 30-minute windows to use for ONNX verification.')
    sample=valid[feat_order].iloc[np.linspace(0,len(valid)-1,min(64,len(valid)),dtype=int)].to_numpy(dtype=np.float32)
    checks={}
    for target in schema['targets']:
        model=joblib.load(folder/schema['model_files'][target])
        converted=to_onnx(model,sample[:1],target_opset=17)
        onnx.checker.check_model(converted)
        path=folder/schema['onnx_files'][target]
        path.write_bytes(converted.SerializeToString())
        sess=ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
        output=sess.run(None,{sess.get_inputs()[0].name:sample})[0]
        onnx_predictions=np.asarray(output,dtype=float).reshape(-1)
        sklearn_predictions=np.asarray(model.predict(sample),dtype=float).reshape(-1)
        discrepancy=np.abs(onnx_predictions-sklearn_predictions)
        worst=float(discrepancy.max())
        tolerance=max(0.2,0.005*float(np.max(np.abs(sklearn_predictions))))
        checks[target]={'max_absolute_difference':worst,'tolerance':tolerance,'verified_rows':len(sample), 'onnx_file':str(path)}
        if worst>tolerance:
            raise RuntimeError(f'{target}: ONNX difference {worst:.4f} > permitted {tolerance:.4f}. Do not deploy this export.')
        print(f'PASS: {target} | {len(sample)} real input rows | max discrepancy {worst:.6f} | {path}')
    (folder/'onnx_validation.json').write_text(json.dumps(checks,indent=2),encoding='utf-8')
    print('All ONNX exports checked. Match ALL ordered float32 features in Android before use.')

if __name__=='__main__':main()
