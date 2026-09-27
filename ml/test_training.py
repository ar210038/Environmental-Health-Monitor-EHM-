"""Small offline tests: python -m unittest test_training.py"""
import unittest
from pathlib import Path
import numpy as np
from train_ehm import load_clean_csv,build_examples,heat_index_celsius

class EHMTrainingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        dataset=Path(__file__).resolve().parent/'data'/'EHM_Dataset_2026-09-27.csv'
        cls.df,cls.glitches,cls.report=load_clean_csv(dataset)
        cls.examples,cls.features=build_examples(cls.df,60)

    def test_two_isolated_glitches_removed(self):
        self.assertEqual(len(self.glitches),2)
        self.assertEqual(len(self.df),3032)

    def test_no_missing_features_or_targets(self):
        self.assertGreater(len(self.examples),300)
        self.assertEqual(len(self.features),32)
        self.assertFalse(self.examples[self.features].isna().any().any())

    def test_target_is_nearly_one_hour_ahead(self):
        d=self.examples.actual_horizon_min
        self.assertGreaterEqual(float(d.min()),58)
        self.assertLessEqual(float(d.max()),62)
        self.assertTrue((self.examples.target_timestamp>self.examples.timestamp).all())

    def test_all_features_and_future_targets_same_session(self):
        keys=self.df[['timestamp','deviceId','session']]
        lookup=keys.merge(self.examples[['timestamp','deviceId','session','target_timestamp']],
                          on=['timestamp','deviceId','session'],how='right',validate='one_to_one')
        matched=lookup.merge(keys.rename(columns={'timestamp':'target_timestamp','session':'target_session'}),
                             on=['deviceId','target_timestamp'],how='left',validate='many_to_one')
        self.assertTrue((matched.session==matched.target_session).all())

    def test_heat_index_output_is_finite(self):
        h=heat_index_celsius(np.array([25,30,32]),np.array([60,75,85]))
        self.assertTrue(np.isfinite(h).all())
        self.assertGreater(h[-1],h[0])

if __name__=='__main__':unittest.main()
