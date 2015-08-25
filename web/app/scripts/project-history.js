// The project history viewer/editor.
'use strict';

angular.module('biggraph').directive('projectHistory', function(util) {
  return {
    restrict: 'E',
    scope: { show: '=', side: '=' },
    templateUrl: 'project-history.html',
    link: function(scope) {
      scope.$watch('show', getHistory);
      scope.$watch('side.state.projectName', getHistory);
      function getHistory() {
        if (!scope.show) { return; }
        scope.remoteChanges = false;
        scope.history = util.nocache('/ajax/getHistory', {
          project: scope.side.state.projectName,
        });
        scope.updatedHistory = undefined;
      }

      function update() {
        scope.localChanges = false;
        scope.valid = true;
        var history = scope.history;
        if (history && history.$resolved && !history.$error) {
          for (var i = 0; i < history.steps.length; ++i) {
            var step = history.steps[i];
            step.localChanges = false;
            step.editable = scope.valid;
            if ((step.checkpoint === undefined) && !step.status.enabled) {
              scope.valid = false;
            }
            watchStep(i, step);
          }
        }
      }
      scope.$watch('history', update);
      scope.$watch('history.$resolved', update);
      scope.$on('apply operation', validate);

      function watchStep(index, step) {
        util.deepWatch(
          scope,
          function() { return step.request; },
          function(after, before) {
            if (after === before) { return; }

            // If all what happened is defining some previously undefined parameters then
            // we don't take this as a change. This kind of change is always done by
            // operation.js (not by the user). It basically sets the defaults provided by the
            // backend for all undefined values. We assume having the default value is equivalent
            // with not having the parameters defined for these operations.
            // For why this is necessary, see: https://github.com/biggraph/biggraph/issues/1985
            var beforeAllDefined = angular.copy(before);
            var beforeParams = beforeAllDefined.op.parameters;
            var afterParams = after.op.parameters;
            angular.forEach(afterParams, function(value, param) {
              if (beforeParams[param] === undefined) {
                beforeParams[param] = value;
              }
            });
            if (angular.equals(after, beforeAllDefined)) { return; }

            step.localChanges = true;
            scope.localChanges = true;
            // Steps after a change cannot use checkpoints.
            // This is visually communicated as well.
            var steps = scope.history.steps;
            for (var i = index; i < steps.length; ++i) {
              steps[i].checkpoint = undefined;
            }
            for (i = 0; i < steps.length; ++i) {
              if (i !== index) {
                steps[i].editable = false;
              }
            }
          });
      }

      function alternateHistory() {
        var requests = [];
        var steps = scope.history.steps;
        var lastCheckpoint = '';
        for (var i = 0; i < steps.length; ++i) {
          var s = steps[i];
          if (requests.length === 0 && s.checkpoint !== undefined) {
            lastCheckpoint = s.checkpoint;
          } else {
            requests.push(s.request);
          }
        }
        return {
          startingPoint: lastCheckpoint,
          requests: requests,
        };
      }

      function validate() {
        scope.validating = true;
        scope.updatedHistory = util.post('/ajax/validateHistory', alternateHistory());
      }
      function copyUpdate() {
        if (scope.updatedHistory && scope.updatedHistory.$resolved) {
          scope.remoteChanges = true;
          scope.validating = false;
          scope.history = scope.updatedHistory;
        }
      }
      scope.$watch('updatedHistory', copyUpdate);
      scope.$watch('updatedHistory.$resolved', copyUpdate);

      scope.saveAs = function(newName) {
        scope.saving = true;
        util.post('/ajax/saveHistory', {
          oldProject: scope.side.state.projectName,
          newProject: newName,
          history: alternateHistory(),
        }, function() {
          // On success.
          if (scope.side.state.projectName === newName) {
            scope.side.reload();
          } else {
            scope.side.state.projectName = newName; // Will trigger a reload.
          }
          scope.side.showHistory = false;
        }).$status.then(function() {
          // On completion, regardless of success.
          scope.saving = false;
        });
      };

      scope.reportError = function() {
        util.reportRequestError(scope.history);
      };

      scope.findCategory = function(cats, req) {
        for (var i = 0; i < cats.length; ++i) {
          for (var j = 0; j < cats[i].ops.length; ++j) {
            var op = cats[i].ops[j];
            if (req.op.id === op.id) {
              return cats[i];
            }
          }
        }
        return undefined;
      };

      function findOp(cats, opId) {
        for (var i = 0; i < cats.length; ++i) {
          for (var j = 0; j < cats[i].ops.length; ++j) {
            var op = cats[i].ops[j];
            if (opId === op.id) {
              return op;
            }
          }
        }
        return undefined;
      }

      function opNamesForSteps(steps) {
        var names = [];
        for (var i = 0; i < steps.length; ++i) {
          var step = steps[i];
          var op = findOp(step.opCategoriesBefore, step.request.op.id);
          if (op) {
            names.push(findOp(step.opCategoriesBefore, step.request.op.id).title);
          }
        }
        return names;
      }

      scope.listInvalidSteps = function() {
        var invalids = opNamesForSteps(scope.history.steps.filter(
              function(step) { return !step.status.enabled; }));
        if (invalids.length === 0) {
          return '';  // No name for the invalid step.
        }
        var is = invalids.length === 1 ? 'is' : 'are';
        return '(' + invalids.join(', ') + ' ' + is + ' invalid)';
      };

      // Confirm leaving the history page if changes have been made.
      scope.$watch('show && (localChanges || remoteChanges)', function(changed) {
        scope.changed = changed;
        window.onbeforeunload = !changed ? null : function(e) {
          e.returnValue = 'Your history changes are unsaved.';
          return e.returnValue;
        };
      });
      scope.closeHistory = function() {
        if (!scope.changed || window.confirm('Discard history changes?')) {
          scope.side.showHistory = false;
        }
      };
      scope.closeProject = function() {
        if (!scope.changed || window.confirm('Discard history changes?')) {
          scope.side.close();
        }
      };

      // Discard operation.
      scope.discard = function(step) {
        var pos = scope.history.steps.indexOf(step);
        scope.history.steps.splice(pos, 1);
        validate();
      };

      // Insert new operation.
      scope.insertBefore = function(step, seg) {
        var pos = scope.history.steps.indexOf(step);
        scope.history.steps.splice(pos, 0, blankStep(seg));
        validate();
      };
      scope.insertAfter = function(step, seg) {
        var pos = scope.history.steps.indexOf(step);
        scope.history.steps.splice(pos + 1, 0, blankStep(seg));
        validate();
      };

      // Returns the short name of the segmentation if the step affects a segmentation.
      scope.segmentationName = function(step) {
        var path = step.request.path;
        if (path.length === 0) { // This is the top-level project.
          return undefined;
        } else {
          return path[path.length - 1];
        }
      };

      scope.workflowMode = { enabled: false };
      scope.enterWorkflowSaving = function() {
        var history = scope.history;
        if (history && history.$resolved && !history.$error) {
          var requests = history.steps.map(function(step) {
            return step.request;
          });
          scope.code = toGroovy(requests);
        } else {
          scope.code = '';
        }
        scope.workflowMode.enabled = true;
      };

      function blankStep(seg) {
        var project = scope.side.state.projectName;
        if (seg !== undefined) {
          project = seg.fullName;
        }
        return {
          request: {
            path: [],
            op: {
              id: 'No-operation',
              parameters: {},
            },
          },
          status: {
            enabled: false,
            disabledReason: 'Fetching valid operations...',
          },
          segmentationsBefore: [],
          segmentationsAfter: [],
          opCategoriesBefore: [],
        };
      }

      function toGroovyId(name) {
        return name
          .replace(/^./, function(first) { return first.toLowerCase(); })
          .replace(/-./g, function(dashed) { return dashed[1].toUpperCase(); });
      }

      function toGroovy(requests) {
        var lines = [];
        for (var i = 0; i < requests.length; ++i) {
          var request = requests[i];
          var line = [];
          line.push('project');
          for (var j = 0; j < request.path.length; ++j) {
            var seg = request.path[j];
            line.push('.segmentations[\'' + seg + '\']');
          }
          line.push('.' + toGroovyId(request.op.id) + '(');
          var params = Object.keys(request.op.parameters);
          params.sort();
          for (j = 0; j < params.length; ++j) {
            var k = params[j];
            var v = request.op.parameters[k];
            if (!k.match(/^[a-zA-Z]+$/)) {
              k = JSON.stringify(k);
            }
            v = JSON.stringify(v);
            line.push(k + ': ' + v);
            if (j !== params.length - 1) {
              line.push(', ');
            }
          }
          line.push(')');
          lines.push(line.join(''));
        }
        return lines.join('\n');
      }
    },
  };
});
