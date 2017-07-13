// Presents the parameters of an operation. It picks the right presentation
// (text box, dropdown, etc) based on the parameter metadata.
'use strict';

angular.module('biggraph').directive('operationParameters', function(util) {
  return {
    restrict: 'E',
    scope: {
      box: '=',
      meta: '=',
      output: '=',
      parametric: '=',
      pflags: '=',
      onBlur: '&',
      busy: '=?',
      editable: '=',
    },
    templateUrl: 'scripts/operation/operation-parameters.html',
    link: function(scope, element) {
      element.on('focusout', function() { scope.onBlur(); });
      scope.fileUploads = { count: 0 };
      scope.$watch('fileUploads.count', function(count) {
        scope.busy = count !== 0;
      });


      util.deepWatch(scope, 'pflags', function(flags) {
        for (var v in flags) {
          if (flags[v] === true) {
            util.move(v, scope.output, scope.parametric);
          } else {
            util.move(v, scope.parametric, scope.output);
          }
        }
        scope.onBlur();
      });

      // Translate between arrays and comma-separated strings for multiselects.
      scope.multiOutput = {};
      util.deepWatch(scope, 'output', function(output) {
        for (var i = 0; i < scope.meta.parameters.length; ++i) {
          var param = scope.meta.parameters[i];
          if (param.options.length > 0 && param.multipleChoice) {
            var flat = output[param.id];
            if (flat !== undefined && flat.length > 0) {
              scope.multiOutput[param.id] = flat.split(',');
            } else {
              scope.multiOutput[param.id] = [];
            }
          }
        }
      });

      util.deepWatch(scope, 'multiOutput', function(multiOutput) {
        for (var i = 0; i < scope.meta.parameters.length; ++i) {
          var param = scope.meta.parameters[i];
          if (param.options.length > 0 && param.multipleChoice) {
            scope.output[param.id] = (multiOutput[param.id] || []).join(',');
          }
        }
      });

      scope.onLoad = function(editor) {
        editor.getSession().setTabSize(2);
        editor.renderer.setScrollMargin(7, 6);
        editor.setOptions({
          highlightActiveLine: false,
          maxLines: 50,
        });
        editor.commands.addCommand({
          name: 'blur',
          bindKey: {
            win: 'Ctrl-Enter',
            mac: 'Command-Enter',
            sender: 'editor|cli'
          },
          exec: function() {
            scope.$apply(function() {
              scope.onBlur();
            });
          }
        });
      };

      scope.isVisualizationParam = function(param) {
        return param.kind === 'visualization' && !scope.pflags[param.id];
      };
    }
  };
});
