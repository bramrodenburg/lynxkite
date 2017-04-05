import unittest
import lynx
import random
import string
import os
import shutil


class TestParquetPartitioning(unittest.TestCase):

  def do_test_parquet_partitioning(self, partitions, expected_partitions):
    path = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(15))
    lk = lynx.LynxKite()
    p = lk.new_project()

    size = 1000
    p.newVertexSet(size=size)
    sql = 'SELECT ordinal from `p`'
    view = lk.sql(sql, p=p)

    data_path = "DATA$/" + path
    view.export_parquet(data_path, partitions)
    # Check number of parquet files:
    resolved_path = lk.get_prefixed_path(data_path).resolved
    # Cut file: from the beginning
    raw_path = resolved_path[5:]
    files = os.listdir(raw_path)
    num_files = len(list(filter(lambda file: file.endswith('.snappy.parquet'), files)))
    self.assertEqual(num_files, expected_partitions)

    # Check data integrity
    view2 = lk.import_parquet(data_path)
    result = lk.sql('select SUM(ordinal) as s from v', v=view2)
    ordinal_sum = result.take(1)[0]['s']
    self.assertEqual(ordinal_sum, size * (size - 1) / 2)

    # Clean up, if everything was okay
    shutil.rmtree(raw_path)

  def test_parquet_partitioning(self):
    self.do_test_parquet_partitioning(None, 1)
    self.do_test_parquet_partitioning(150, 150)
    self.do_test_parquet_partitioning(200, 200)
    self.do_test_parquet_partitioning(230, 230)

if __name__ == '__main__':
  unittest.main()
