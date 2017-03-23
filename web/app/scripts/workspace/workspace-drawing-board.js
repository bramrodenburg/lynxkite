'use strict';

// The drawing board where the user can create and modify a boxes and
// arrows diagram.

angular.module('biggraph')
  .directive('workspaceDrawingBoard', function(workspaceManager) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/workspace-drawing-board.html',
      templateNamespace: 'svg',
      scope: {
        workspaceName: '=',
        selectedBox: '=',
        selectedState: '=',
        boxCatalog: '=',
      },
      link: function(scope, element) {
        scope.$watchGroup(
          ['boxCatalog.$resolved', 'workspaceName'],
          function() {
            if (scope.boxCatalog.$resolved && scope.workspaceName) {
              scope.manager = workspaceManager(
                  scope.boxCatalog, scope.workspaceName);
            }
        });

        var workspaceDrag = false;
        var workspaceX = 0;
        var workspaceY = 0;
        var workspaceZoom = 0;
        var mouseX = 0;
        var mouseY = 0;
        function zoomToScale(z) { return Math.exp(z * 0.001); }
        function getLogicalPosition(event) {
          return {
            x: (event.offsetX - workspaceX) / zoomToScale(workspaceZoom),
            y: (event.offsetY - workspaceY) / zoomToScale(workspaceZoom) };
        }
        scope.onMouseMove = function(event) {
          event.preventDefault();
          if (workspaceDrag) {
            workspaceX += event.offsetX - mouseX;
            workspaceY += event.offsetY - mouseY;
          }
          mouseX = event.offsetX;
          mouseY = event.offsetY;
          scope.manager.onMouseMove(getLogicalPosition(event));
        };

        scope.onMouseDownOnBox = function(box, event) {
          event.stopPropagation();
          scope.manager.onMouseDownOnBox(box, getLogicalPosition(event));
        };

        scope.onMouseUp = function(event) {
          workspaceDrag = false;
          element[0].style.cursor = '';
          scope.manager.onMouseUp(event);
        };

        scope.onMouseDown = function(event) {
          event.preventDefault();
          workspaceDrag = true;
          setGrabCursor(element[0]);
          mouseX = event.offsetX;
          mouseY = event.offsetY;
        };
        
        scope.workspaceTransform = function() {
          var z = zoomToScale(workspaceZoom);
          return 'translate(' + workspaceX + ', ' + workspaceY + ') scale(' + z + ')';
        };

        function setGrabCursor(e) {
          // Trying to assign an invalid cursor will silently fail. Try to find a supported value.
          e.style.cursor = '';
          e.style.cursor = 'grabbing';
          if (!e.style.cursor) {
            e.style.cursor = '-webkit-grabbing';
          }
          if (!e.style.cursor) {
            e.style.cursor = '-moz-grabbing';
          }
        }

        element.on('wheel', function(event) {
          event.preventDefault();
          var delta = event.originalEvent.deltaY;
          if (/Firefox/.test(window.navigator.userAgent)) {
            // Every browser sets different deltas for the same amount of scrolling.
            // It is tiny on Firefox. We need to boost it.
            delta *= 20;
          }
          scope.$apply(function() {
            var z1 = zoomToScale(workspaceZoom);
            workspaceZoom -= delta;
            var z2 = zoomToScale(workspaceZoom);
            // Maintain screen-coordinates of logical point under the mouse.
            workspaceX = mouseX - (mouseX - workspaceX) * z2 / z1;
            workspaceY = mouseY - (mouseY - workspaceY) * z2 / z1;
          });
        });
        element.bind('dragover', function(event) {
          event.preventDefault();
        });
        element.bind('drop', function(event) {
          event.preventDefault();
          var origEvent = event.originalEvent;
          var operationID = event.originalEvent.dataTransfer.getData('text');
          // This is received from operation-selector-entry.js
          scope.$apply(function() {
            scope.manager.addBox(operationID, getLogicalPosition(origEvent));
          });
        });

        scope.$on('$destroy', function() {
          scope.manager.stopProgressUpdate();
        });
      }
    };
  });
