'use strict';

// Viewer of a state at an output of a box.

angular.module('biggraph')
 .directive('stateView', function(side, util) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/state-view.html',
      scope: {
        workspace: '='
      },
      link: function(scope) {
        scope.$watch('state.$resolved', function() {
          scope.createSnapshot = function(saveAsName) {
            scope.saving = true;
            util.post('/ajax/createSnapshot', {
              name: saveAsName,
              id: scope.side.stateID
            }).finally(function() {
              scope.saving = false;
            });
          };
          if (scope.state && scope.state.$resolved &&
              scope.state.kind === 'project') {
            scope.side = new side.Side([], '');
            scope.side.project = scope.state.project;
            scope.side.stateID = scope.stateId;
            scope.side.project.$resolved = true;
            scope.side.onProjectLoaded();
          } else {
            scope.side = undefined;
          }
        });
      },
    };
});
