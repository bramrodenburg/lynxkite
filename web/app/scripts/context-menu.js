'use strict';

// A context menu for graph vertices.
angular.module('biggraph').directive('contextMenu', function() {
  return {
    replace: true,
    restrict: 'E',
    scope: { model: '=' },
    templateUrl: 'context-menu.html',
    link: function(scope, element) {
      scope.$watch('model.enabled', function(enabled) {
        if (enabled) {
          // Whenever the menu opens we set focus on it. We need this so we can close it on blur
          // (that is, if the user mouse downs anywhere outside).
          element.focus();
        }
      });
      scope.executeAction = function(action) {
        action.callback(scope.model.data);
        scope.model.enabled = false;
      };
    },
  };
});
