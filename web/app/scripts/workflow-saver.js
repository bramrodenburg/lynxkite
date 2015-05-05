// Presents the parameters for saving an operation.
'use strict';

angular.module('biggraph').directive('workflowSaver', function(util) {
  return {
    restrict: 'E',
    scope: { code: '=' },
    templateUrl: 'workflow-saver.html',
    link: function(scope) {
      scope.save = function() {
        util.post('/ajax/saveWorkflow', {
          workflowName: scope.name,
          stepsAsJSON: scope.code,
          description: scope.description,
        });
      };
    }
  };
});
