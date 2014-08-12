'use strict';

angular.module('biggraph').directive('operation', function ($resource, util) {
  return {
    restrict: 'E',
    scope: { op: '=', side: '=' },
    replace: false,
    templateUrl: 'operation.html',
    link: function(scope) {
      scope.params = {};
      scope.multiParams = {};
      util.deepWatch(scope, 'op', function() {
        scope.op.parameters.forEach(function(p) {
          if (p.options.length === 0) {
            scope.params[p.id] = p.defaultValue;
          } else {
            if (!p.multipleChoice) {
              scope.params[p.id] = p.options[0].id;
            }
          }
        });
      });

      scope.apply = function() {
        if (!scope.op.enabled.success) { return; }
        var reqParams = {};
        scope.op.parameters.forEach(function(p) {
          if (p.multipleChoice) {
            reqParams[p.id] = scope.multiParams[p.id].join(',');
          } else {
            reqParams[p.id] = scope.params[p.id];
          }
        });
        var req = { project: scope.side.project.name, op: { id: scope.op.id, parameters: reqParams } };
        scope.running = true;
        // TODO: Report errors on the UI.
        $resource('/ajax/projectOp').save(req, function(result) {
          scope.running = false;
          if (result.success) {
            scope.side.reload();
          } else {
            console.error(result.failureReason);
          }
        }, function(response) {
          scope.running = false;
          console.error(response);
        });
      };
    }
  };
});
