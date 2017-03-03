#!/usr/bin/python3

import json
import os
import re
import sys


assert len(sys.argv) == 2, 'Exactly 1 argument is expected. Please specify the kite_meta directory.'

meta_dir = sys.argv[1]  # The kite_meta directory.

canonical_guid_representation = re.compile(r'[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}')
guids_to_keep = set()  # This
operations_to_delete = set()  # and this will be filled later.


class Dependency:
  # This class represents an entity's dependencies on the inputs of its respective operation.

  def __init__(self, entity_guid, containing_operation, input_entity_guids):
    self.guid = entity_guid
    self.operation = containing_operation
    self.inputs = input_entity_guids
    self.checked = False

  def crawl_upwards(self):
    if not self.checked:
      operations_to_delete.discard(self.operation)
      self.checked = True
      for i in self.inputs:
        i.crawl_upwards()

# Dependency graph of entities, similar to LynxKite's metagraph.
meta_graph = {}

for version in sorted(os.listdir(meta_dir)):  # Sorting in necessary
  # Parse operations, store operation_file names, construct metagraph.
  operations_dir = os.path.join(meta_dir, version, 'operations')
  for operation_file in sorted(os.listdir(operations_dir)):
    operations_to_delete.add(operation_file)
    with open(operations_dir + '/' + operation_file) as operation:
      op_json = json.load(operation)
      for o in op_json["outputs"].values():
        # This following line is the reason we need to sort the os.listdir()-s.
        # We want to have the inputs ready to construct a new Dependency.
        meta_graph[o] = Dependency(o, operation_file, [meta_graph[i]
                                                       for i in op_json["inputs"].values()])
  # Parse checkpoints for guids we want to keep.
  checkpoints_dir = os.path.join(meta_dir, version, 'checkpoints')
  for checkpoint_file in os.listdir(checkpoints_dir):
    with open(checkpoints_dir + '/' + checkpoint_file) as checkpoint:
      guids_to_keep.update(canonical_guid_representation.findall(checkpoint.read()))

# Crawl the dependency graph, remove elements we want to keep from the operations_to_delete list.
# We want to keep an operation iff we want to keep at least one of its (transitive-)output guids.
while guids_to_keep:
  guid = guids_to_keep.pop()
  meta_graph[guid].crawl_upwards()

for op in operations_to_delete:
  print(op)
