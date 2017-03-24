'use strict';

// Viewer and editor of a box instance.

angular.module('biggraph')
 .directive('boxEditor', function(util) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/box-editor.html',
      scope: {
        workspace: '=',
      },
      link: function(scope) {
        scope.$watch('workspace', function() {
          // We are not expecting updates of this it will be
          // only set once at setup time. The $watch is just
          // needed to be able to see the initial value.
          if (scope.workspace) {
            // This callback will fire in two cases: if the
            // box ID is changed or if the workspace (and therefore,
            // possibly the box meta is changed).
            scope.workspace.setBoxSelectionCallback(
                function() { scope.loadBoxMeta(); });
          }
        });
        scope.$watch(
            'workspace.selectedBoxId',
            function() {
              if (!scope.workspace) {
                return;
              }
              // Make a copy of the parameter values.
              scope.paramValues = Object.assign(
                  {}, scope.workspace.selectedBox().instance.parameters);
              scope.loadBoxMeta();
            });
        // The metadata (param definition list) of the current box
        // depends on the whole workspace. (Attributes added by
        // previous operations, state of apply_to_ parameters of
        // current box.) If this deepwatch is a performance problem,
        // then we can put a timestamp in workspace and watch that,
        // or only deepwatch the current selected box (and assume
        // box selection has to change to edit other boxes).
        scope.$watch(
            'workspace.workspace',
            function() {
              if (!scope.workspace) {
                return;
              }
              scope.loadBoxMeta();
            },
            true);

        scope.paramValues = {};

        scope.loadBoxMeta = function() {
          if (!scope.workspace || !scope.workspace.selectedBoxId) {
            return;
          }
          // The below magic makes sure that the response
          // to the result of the latest getOperationMetaRequest
          // will be passed to scope.newOpSelected().
          var currentRequest;
          scope.lastRequest = currentRequest = util
            .nocache(
              '/ajax/getOperationMeta',
              {
                  workspace: scope.workspace.name,
                  box: scope.workspace.selectedBoxId,
              })
            .then(
              function success(boxMeta) {
                if (scope.lastRequest === currentRequest) {
                  scope.newOpSelected(boxMeta);
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
        scope.newOpSelected = function(boxMeta) {
            scope.boxMeta = boxMeta;
            if (!scope.boxMeta) {
              return;
            }

            // Populate parameter values.
            for (var i = 0; i < boxMeta.parameters.length; ++i) {
              var p = boxMeta.parameters[i];
              if (scope.paramValues[p.id] !== undefined) {
                // Parameter is set externally.
              } else if (p.options.length === 0) {
                scope.paramValues[p.id] = p.defaultValue;
              } else if (p.multipleChoice) {
                scope.paramValues[p.id] = '';
              } else {
                scope.paramValues[p.id] = p.options[0].id;
              }
            }
        };

        scope.apply = function() {
          scope.workspace.updateSelectedBox(scope.paramValues);
        };
      },
    };
});
