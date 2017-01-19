import unittest
import lynx


class TestProjects(unittest.TestCase):

  def test_save_and_load_project(self):
    lk = lynx.LynxKite()
    lk._request('/ajax/discardAllReallyIMeanIt')
    p = lk.new_project()
    p.exampleGraph()
    p.save('test_project')
    p2 = lk.load_project('test_project')
    self.assertTrue(p2.checkpoint is not None)
    self.assertEqual(p.checkpoint, p2.checkpoint)

  def test_get_scalar(self):
    lk = lynx.LynxKite()
    p = lk.new_project()
    p.exampleGraph()
    greeting = p.scalar('greeting')
    self.assertEqual(greeting, 'Hello world! 😀 ')

  def test_compute(self):
    p = lynx.LynxKite().new_project()
    p.newVertexSet(size=10)
    p.compute()
    self.assertTrue(p.is_computed())

  def test_table_name(self):
    p1 = lynx.LynxKite().new_project()
    p1.exampleGraph()
    p2 = lynx.LynxKite().new_project()
    p2.importVertices(**{'id-attr': 'id', 'table': p1.table_name('edges')})
    self.assertEqual(4, p2.scalar('vertex_count'))


if __name__ == '__main__':
  unittest.main()
