import unittest
import lynx


class TestListing(unittest.TestCase):

  def test_listing(self):
    lk = lynx.LynxKite()
    lk._request('/ajax/discardAllReallyIMeanIt')
    lk.new_project().save('dir/entry')
    lk.new_project().save('root_project')
    lk.sql('select 1').save('root_view')
    root_list = lk.list_dir()
    self.assertEqual(len(root_list), 3)

    views = [e.name for e in root_list if e.type == 'view']
    self.assertEqual(views, ['root_view'])

    dir = [e for e in root_list if e.type == 'directory'][0]
    inner_list = dir.object.list()

    self.assertEqual(inner_list[0].name, 'dir/entry')
    self.assertEqual(inner_list[0].type, 'project')

    dir_alternate = lk.list_dir('dir')
    self.assertEqual(dir_alternate[0].name, 'dir/entry')


if __name__ == '__main__':
  unittest.main()