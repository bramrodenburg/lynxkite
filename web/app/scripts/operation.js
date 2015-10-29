// Displays an operation (title, description, parameters, OK button).
'use strict';

angular.module('biggraph').directive('operation', function(util, hotkeys /*, $timeout */) {
  return {
    restrict: 'E',
    scope: {
      op: '=',  // (Input.)
      color: '=',  // (Input.)
      editable: '=',  // (Input.)
      params: '=',  // (Input/output.)
      applying: '=?',  // (Input.) Whether an operation is being applied currently.
      sideWorkflowEditor: '=',  // (Input/output.) The workflow editor available on this side.
    },
    templateUrl: 'operation.html',
    link: function(scope, element) {
      scope.fileUploads = {};
      scope.$watch('op.parameters', function() {
        for (var i = 0; i < scope.op.parameters.length; ++i) {
          var p = scope.op.parameters[i];
          if (scope.params[p.id] !== undefined) {
            // Parameter is set externally.
          } else if (p.options.length === 0) {
            scope.params[p.id] = p.defaultValue;
          } else if (p.multipleChoice) {
            scope.params[p.id] = '';
          } else {
            scope.params[p.id] = p.options[0].id;
          }
        }
      });

      scope.apply = function() {
        if (!scope.op.status.enabled || scope.applying || scope.busy) {
          return;
        }
        scope.$emit('apply operation');
      };
      hotkeys.bindTo(scope).add({
        combo: 'enter',
        callback: scope.apply,
      });
      scope.editOperation = function() {
        scope.sideWorkflowEditor.enabled = true;
        scope.sideWorkflowEditor.workflow =
          util.get('/ajax/workflow', { id: scope.op.id });
      };
      scope.operationIsEditable = function() {
        return scope.op.isWorkflow && scope.sideWorkflowEditor;
      };

      // Focus the first input box when the operation is opened.
      scope.$watch(function() {
        // Have to watch for the parameters to finish rendering.
        return element.find('input, select')[0];
      }, function(firstInput) {
        if (firstInput) {
          if (firstInput.select) {
            firstInput.select();
          } else {
            firstInput.focus();
          }
        } else {
          // No parameters? Focus on the OK button.
          element.find('.ok-button').focus();
        }
      });
    }
  };
});
