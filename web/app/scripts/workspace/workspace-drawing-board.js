'use strict';

// The drawing board where the user can create and modify a boxes and
// arrows diagram.

angular.module('biggraph')
  .directive('workspaceDrawingBoard', function(hotkeys, $rootScope) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/workspace-drawing-board.html',
      templateNamespace: 'svg',
      scope: {
        guiMaster: '=',
      },
      link: function(scope, element) {
        var workspaceDrag = false;
        var selectBoxes = false;
        var workspaceX = 0;
        var workspaceY = 0;
        var workspaceZoom = 0;
        var mouseX = 0;
        var mouseY = 0;
        function zoomToScale(z) { return Math.exp(z * 0.001); }
        function addLogicalMousePosition(event) {
          /* eslint-disable no-console */
          console.assert(!('logicalX' in event) && !('logicalY' in event));
          console.assert(!('workspaceX' in event) && !('workspaceY' in event));
          // event.offsetX/Y are distorted when the mouse is
          // over a popup window (even if over an invisible
          // overflow part of it), hence we compute our own:
          event.workspaceX = event.pageX - element.offset().left;
          event.workspaceY = event.pageY - element.offset().top;
          // Add location according to pan and zoom:
          var logical = scope.pageToLogical({ x: event.pageX, y: event.pageY });
          event.logicalX = logical.x;
          event.logicalY = logical.y;
          return event;
        }

        scope.pageToLogical = function(pos) {
          var z = zoomToScale(workspaceZoom);
          var off = element.offset();
          return {
            x: (pos.x - off.left - workspaceX) / z,
            y: (pos.y - off.top - workspaceY) / z,
          };
        };

        scope.logicalToPage = function(pos) {
          var z = zoomToScale(workspaceZoom);
          var off = element.offset();
          return {
            x: pos.x * z + workspaceX + off.left,
            y: pos.y * z + workspaceY + off.top,
          };
        };

        function actualDragMode(event) {
          var dragMode = (window.localStorage.getItem('drag_mode') || 'pan');
          // Shift chooses the opposite mode.
          if (dragMode === 'select') {
            return event.shiftKey ? 'pan' : 'select';
          } else {
            return event.shiftKey ? 'select' : 'pan';
          }
        }

        scope.onMouseMove = function(event) {
          event.preventDefault();
          addLogicalMousePosition(event);
          if (workspaceDrag) {
            workspaceX += event.workspaceX - mouseX;
            workspaceY += event.workspaceY - mouseY;
          } else if (selectBoxes) {
            scope.guiMaster.selection.endX = event.logicalX;
            scope.guiMaster.selection.endY = event.logicalY;
            scope.guiMaster.updateSelection();
            scope.guiMaster.selectBoxesInSelection();
          }
          mouseX = event.workspaceX;
          mouseY = event.workspaceY;
          scope.guiMaster.onMouseMove(event);
        };

        scope.onMouseDownOnBox = function(box, event) {
          event.stopPropagation();
          addLogicalMousePosition(event);
          scope.guiMaster.removeSelection();
          scope.guiMaster.onMouseDownOnBox(box, event);
        };

        scope.onMouseUp = function(event) {
          element[0].style.cursor = '';
          workspaceDrag = false;
          selectBoxes = false;
          scope.guiMaster.removeSelection();
          addLogicalMousePosition(event);
          scope.guiMaster.onMouseUp(event);
        };

        scope.onMouseDown = function(event) {
          var dragMode = actualDragMode(event);
          event.preventDefault();
          addLogicalMousePosition(event);
          if (dragMode === 'pan') {
            workspaceDrag = true;
            setGrabCursor(element[0]);
            mouseX = event.workspaceX;
            mouseY = event.workspaceY;
          } else if (dragMode === 'select') {
            selectBoxes = true;
            scope.guiMaster.selectedBoxIds = [];
            scope.guiMaster.selection.endX = event.logicalX;
            scope.guiMaster.selection.endY = event.logicalY;
            scope.guiMaster.selection.startX = event.logicalX;
            scope.guiMaster.selection.startY = event.logicalY;
            scope.guiMaster.updateSelection();
          }
        };

        scope.workspaceTransform = function() {
          var z = zoomToScale(workspaceZoom);
          return 'translate(' + workspaceX + ', ' + workspaceY + ') scale(' + z + ')';
        };

        var hk = hotkeys.bindTo(scope);
        hk.add({
          combo: 'ctrl+c', description: 'Copy boxes',
          callback: function() { scope.guiMaster.copyBoxes(); } });
        hk.add({
          combo: 'ctrl+v', description: 'Paste boxes',
          callback: function() {
            scope.guiMaster.pasteBoxes(addLogicalMousePosition({ pageX: 0, pageY: 0}));
          } });
        hk.add({
          combo: 'ctrl+z', description: 'Undo',
          callback: function() {
            scope.guiMaster.undo();
          } });
        hk.add({
          combo: 'ctrl+y', description: 'Redo',
          callback: function() {
            scope.guiMaster.redo();
          } });
        hk.add({
          combo: 'del', description: 'Paste boxes',
          callback: function() { scope.guiMaster.deleteSelectedBoxes(); } });
        hk.add({
          combo: '/', description: 'Find operation',
          callback: function(e) {
            e.preventDefault();  // Do not type "/".
            $rootScope.$broadcast('open operation search');
          }});

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

        element.find('svg').on('wheel', function(event) {
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
          // This isn't undefined iff testing
          var boxID = event.originalEvent.dataTransfer.getData('id');
          // This is received from operation-selector-entry.js
          scope.$apply(function() {
            addLogicalMousePosition(origEvent);
            scope.guiMaster.addBox(operationID, origEvent, boxID);
          });
        });

        scope.$on('$destroy', function() {
          scope.guiMaster.stopProgressUpdate();
        });
      }
    };
  });
