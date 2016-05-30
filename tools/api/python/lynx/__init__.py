'''Python interface for the named pipe-based LynxKite API.

Reads the pipe location from .kiterc just like LynxKite does.

Example usage:

    import lynx
    p = lynx.Project()
    p.new_vertex_set(size=100)
    print(p.scalar('vertex_count'))

The operation method names are case and underscore-insensitive. Pick your favorite style.
'''
import atexit
import json
import os
import re
import subprocess
import tempfile
import types


def _init():
  global server_pipe, response_pipe_int, response_pipe_ext
  server_pipe = _getkiterc('KITE_API_PIPE')
  response_dir_int = os.environ.get('KITE_API_PIPE_RESPONSE_INTERNAL') or tempfile.gettempdir()
  response_dir_ext = os.environ.get('KITE_API_PIPE_RESPONSE_EXTERNAL') or response_dir_int
  response_pipe_int = '{}/pipe-{}'.format(response_dir_int, os.getpid())
  response_pipe_ext = '{}/pipe-{}'.format(response_dir_ext, os.getpid())
  os.mkfifo(response_pipe_int)
  atexit.register(_cleanup)


def _cleanup():
  os.remove(response_pipe_int)


class Project(object):
  def __init__(self):
    r = _send('newProject')
    self.checkpoint = r.checkpoint

  def scalar(self, scalar):
    r = _send('getScalar', dict(checkpoint=self.checkpoint, scalar=scalar))
    if hasattr(r, 'double'):
      return r.double
    return r.string

  def sql(self, query):
    r = _send('sql', dict(checkpoint=self.checkpoint, query=query), raw=True)
    return r['rows']

  def run_operation(self, operation, parameters):
    r = _send('runOperation',
        dict(checkpoint=self.checkpoint, operation=operation, parameters=parameters))
    self.checkpoint = r.checkpoint

  def __getattr__(self, attr):
    operation = attr.replace('_', '')  # Drop underscores.
    def f(**kwargs):
      params = {}
      for k, v in kwargs.items():
        params[k] = str(v)
      self.run_operation(operation, params)
    return f


class LynxException(Exception):
  def __init__(self, error, command):
    super(LynxException, self).__init__(error)
    self.error = error
    self.command = command


def _send(command, payload={}, raw=False):
  msg = json.dumps(dict(command=command, payload=payload, responsePipe=response_pipe_ext))
  with open(server_pipe, 'w') as p:
    p.write(msg)
  with open(response_pipe_int) as p:
    data = p.read()
    if raw:
      r = json.loads(data)
      if 'error' in r:
        raise LynxException(r['error'], r['request'])
    else:
      r = json.loads(data, object_hook=_asobject)
      if hasattr(r, 'error'):
        raise LynxException(r.error, r.request)
    return r


def _asobject(dic):
  return types.SimpleNamespace(**dic)


def _getkiterc(variable):
  return subprocess.check_output(
      '''
      KITE_SITE_CONFIG=${{KITE_SITE_CONFIG:-$HOME/.kiterc}}
      if [ -f "$KITE_SITE_CONFIG" ]; then
        source "$KITE_SITE_CONFIG"
      fi
      if [ -f "$KITE_SITE_CONFIG_OVERRIDES" ]; then
        source "$KITE_SITE_CONFIG_OVERRIDES"
      fi
      echo ${}
      '''.format(variable), shell=True, executable='/bin/bash').strip()


_init()
