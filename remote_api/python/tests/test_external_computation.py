import lynx.kite
import numpy as np
import os
import pandas as pd
import unittest


class TestExternalComputation(unittest.TestCase):

  def test_pandas(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def title_names(table):
      df = table.pandas()
      df['titled_name'] = np.where(df.gender == 'Female', 'Ms ' + df.name, 'Mr ' + df.name)
      return df

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = title_names(eg)
    t.trigger()
    self.assertTrue(t.sql('select titled_name from input').df().equals(
        pd.DataFrame({'titled_name': [
            'Mr Adam',
            'Ms Eve',
            'Mr Bob',
            'Mr Isolated Joe',
        ]})))

  def test_pyspark(self):
    lk = lynx.kite.LynxKite()
    try:
      from pyspark.sql import SparkSession
    except ImportError:
      print('Skipping PySpark test. "pip install pyspark" if you want to run it.')
      return
    spark = SparkSession.builder.appName('test').getOrCreate()

    @lynx.kite.external
    def stats(table):
      df = table.spark(spark)
      return df.groupBy('gender').count()

    eg = lk.createExampleGraph().sql('select * from vertices')
    t = stats(eg)
    t.trigger()
    self.assertTrue(t.sql('select * from input').df().equals(
        pd.DataFrame({'gender': ['Female', 'Male'], 'count': [1.0, 3.0]})))

  def test_filename(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def title_names(table):
      df = table.pandas()
      df['titled_name'] = np.where(df.gender == 'Female', 'Ms ' + df.name, 'Mr ' + df.name)
      path_lk = 'DATA$/test/external-computation'
      path = lk.get_prefixed_path(path_lk).resolved.replace('file:', '')
      os.makedirs(path, exist_ok=True)
      path = path + '/part-0'
      df.to_parquet(path)
      return path_lk

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = title_names(eg)
    t.trigger()
    self.assertTrue(t.sql('select titled_name from input').df().equals(
        pd.DataFrame({'titled_name': [
            'Mr Adam',
            'Ms Eve',
            'Mr Bob',
            'Mr Isolated Joe',
        ]})))
