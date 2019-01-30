'use strict';

// Viewer of a plot state.

angular.module('biggraph')
  .directive('plotStateView', function(util) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/plot-state-view.html',
      scope: {
        stateId: '=',
        popupModel: '=',
      },
      link: function(scope, element) {
        // We leave some empty space.
        scope.getPlotWidth = function () {
          return scope.popupModel.width - 50;
        };

        // We leave some more empty space for the header.
        scope.getPlotHeight = function () {
          return (scope.popupModel.height || scope.popupModel.maxHeight) - 100;
        };

        scope.updatePlotSpec = function () {
          scope.embedSpec.spec = JSON.parse(scope.plotJSON.value.string);
        };

        // Vega-embed can be configured using `width` and `height` parameters, but
        // unfortunately the size of the axes and the legend is not included in these
        // parameters. So if we want to control the "outer" or "real" size of the plot,
        // we need extra steps:
        //
        // We embed a plot into a hidden DIV to get its real size.
        // From the size of the hidden plot we can compute the difference
        // between the desired and the actual size. If the actual size is larger than
        // the desired size, we will adjust the parameters of the embed call with
        // the difference.
        scope.computeSizeDiff = function() {
          scope.updatePlotSpec();
          const computeSpec = angular.copy(scope.embedSpec);
          // Desired plot size
          computeSpec.spec.width = scope.getPlotWidth();
          computeSpec.spec.height = scope.getPlotHeight();
          const plotElement = element.find('#hidden-plot-div')[0];
          /* global vg */
          vg.embed(plotElement, computeSpec, function() {
            const svg = element.find('#hidden-plot-div .vega svg')[0];
            const w = svg.attributes['width'].value;
            const h = svg.attributes['height'].value;
            // The assumption is that the difference is constant, not linear.
            const diffX = scope.getPlotWidth() - w;
            const diffY = scope.getPlotHeight() - h;
            scope.$apply(function () {
              scope.diffX = diffX < 0 ? diffX : 0;
              scope.diffY = diffY < 0 ? diffY : 0;
            });

          });
        };

        scope.embedPlot = function () {
          scope.updatePlotSpec();
          if (scope.diffX !== undefined && scope.diffY !== undefined) {
            scope.embedSpec.spec.width = scope.getPlotWidth() + scope.diffX;
            scope.embedSpec.spec.height = scope.getPlotHeight() + scope.diffY;
            const plotElement = element.find('#plot-div')[0];
            /* global vg */
            vg.embed(plotElement, scope.embedSpec, function() {});
          }
        };

        scope.$watch('stateId', function(newValue, oldValue, scope) {
          scope.embedSpec = {
            mode: 'vega-lite',
            actions: false,
            renderer: 'svg',
          };

          scope.plot = util.get('/ajax/getPlotOutput', {
            id: scope.stateId
          });
          scope.plot.then(
            function() {
              scope.plotJSON = util.lazyFetchScalarValue(scope.plot.json, true);
            }, function() {}
          );
        });

        scope.$watchGroup([
          'popupModel.width', 'popupModel.height',
          'plotJSON.value.string',
          'diffX', 'diffY'],
        function() {
          if (scope.plotJSON && scope.plotJSON.value && scope.plotJSON.value.string) {
            scope.embedPlot();
          }
        }
        );

        scope.$watch('embedSpec.spec.description', function(title) {
          // Refresh title after embedding the plot
          scope.title = title;
        });

        scope.$watch('plotJSON', function(newValue, oldValue, scope) {
          if (scope.plotJSON && scope.plotJSON.value && scope.plotJSON.value.string) {
            scope.diffX = undefined;
            scope.diffY = undefined;
            scope.computeSizeDiff();
          }
        }, true);

      },
    };
  });
