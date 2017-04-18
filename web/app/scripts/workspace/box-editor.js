'use strict';

// Viewer and editor of a box instance.

angular.module('biggraph')
 .directive('boxEditor', function($timeout, util) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/box-editor.html',
      scope: {
        workspace: '=',
      },
      link: function(scope) {
        scope.$watch(
            'workspace.selectedBoxId',
            function(boxId) {
              scope.loadBoxMeta(boxId);
            });
        // The metadata (param definition list) of the current box
        // depends on the whole workspace. (Attributes added by
        // previous operations, state of apply_to_ parameters of
        // current box.) If this deepwatch is a performance problem,
        // then we can put a timestamp in workspace and watch that,
        // or only deepwatch the current selected box (and assume
        // box selection has to change to edit other boxes).
        util.deepWatch(
            scope,
            'workspace.backendState',
            function() {
              if (!scope.workspace) {
                return;
              }
              scope.loadBoxMeta(scope.workspace.selectedBoxId);
            });

        scope.paramValues = {};

        scope.loadBoxMeta = function(boxId) {
          if (!scope.workspace || !boxId) {
            return;
          }
          var box = scope.workspace.getBox(boxId);
          // Checking currentRequest makes sure that the response
          // to the result of the latest getOperationMetaRequest
          // will be passed to scope.newOpSelected().
          var currentRequest;
          scope.lastRequest = currentRequest = util
            .nocache(
              '/ajax/getOperationMeta',
              {
                  workspace: scope.workspace.name,
                  box: boxId,
              })
            .then(
              function success(boxMeta) {
                if (scope.lastRequest === currentRequest) {
                  scope.newOpSelected(box, boxMeta);
                }
              },
              function error() {
                if (scope.lastRequest === currentRequest) {
                  scope.newOpSelected(undefined);
                }
              });
        };

        // Invoked when the user selects a new operation and its
        // metadata is successfully downloaded.
        scope.newOpSelected = function(box, boxMeta) {
            // We avoid replacing the objects if the data has not changed.
            // This is to avoid recreating the DOM for the parameters. (Which would lose the focus.)
            if (!angular.equals(box, scope.box)) {
              onBlurNow(); // Switching to a different box is also "blur".
              scope.box = box;
            }
            if (!angular.equals(boxMeta, scope.boxMeta)) {
              scope.boxMeta = boxMeta;
            }
            if (!box) {
              return;
            }

            // Make a copy of the parameter values.
            var paramValues = Object.assign({}, box.instance.parameters);
            // Copy defaults for unset parameters.
            for (var i = 0; i < boxMeta.parameters.length; ++i) {
              var p = boxMeta.parameters[i];
              if (paramValues[p.id] !== undefined) {
                // Parameter is not unset.
              } else if (p.options.length === 0) {
                paramValues[p.id] = p.defaultValue;
              } else if (p.multipleChoice) {
                paramValues[p.id] = '';
              } else {
                paramValues[p.id] = p.options[0].id;
              }
            }
            if (!angular.equals(paramValues, scope.paramValues)) {
              scope.paramValues = paramValues;
            }
        };

        function onBlurNow() {
          if (scope.box) {
            scope.workspace.updateBox(scope.box.instance.id, scope.paramValues);
          }
        }

        // Updates the workspace with the parameter changes after allowing for a digest loop to
        // bubble them up from the directives.
        scope.onBlur = function() {
          $timeout(onBlurNow);
        };
      },
    };
});
