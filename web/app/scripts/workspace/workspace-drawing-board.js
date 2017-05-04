'use strict';

// The drawing board where the user can create and modify a boxes and
// arrows diagram.

angular.module('biggraph')
  .directive('workspaceDrawingBoard', function(hotkeys, SelectionModel, environment, PopupModel) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/workspace-drawing-board.html',
      scope: {
        guiMaster: '=',
      },
      link: function(scope, element) {
        scope.selection = new SelectionModel();
        scope.clipboard = [];
        scope.dragMode = window.localStorage.getItem('drag_mode') || 'pan';
        scope.selectedBoxIds = [];
        // If the user is connecting plugs by drawing a line with the
        // mouse, then this points to the plug where the line was
        // started.
        scope.pulledPlug = undefined;
        // The last known position of the mouse, expressed in logical
        // workspace coordinates.
        scope.mouseLogical = undefined;

        scope.$watch(
          'dragMode',
          function(dragMode) {
            window.localStorage.setItem('drag_mode', dragMode);
          });


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
          var board = element.find('#workspace-drawing-board');
          // event.offsetX/Y are distorted when the mouse is
          // over a popup window (even if over an invisible
          // overflow part of it), hence we compute our own:
          event.workspaceX = event.pageX - board.offset().left;
          event.workspaceY = event.pageY - board.offset().top;
          // Add location according to pan and zoom:
          event.logicalX = (event.workspaceX - workspaceX) / zoomToScale(workspaceZoom);
          event.logicalY = (event.workspaceY - workspaceY) / zoomToScale(workspaceZoom);
          return event;
        }

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
            scope.selection.onMouseMove(event);
            scope.selectBoxesInSelection();
          }
          mouseX = event.workspaceX;
          mouseY = event.workspaceY;

          var leftButton = event.buttons & 1;
          // Protractor omits button data from simulated mouse events.
          if (!leftButton && !environment.protractor) {
            // Button is no longer pressed. (It was released outside of the window, for example.)
            this.guiMaster.onMouseUp();
          } else {
            this.mouseLogical = {
              x: event.logicalX,
              y: event.logicalY,
            };
            if (this.guiMaster.movedBoxes) {
              for (var i = 0; i < this.guiMaster.movedBoxes.length; i++) {
                this.guiMaster.movedBoxes[i].onMouseMove(event);
              }
            } else if (this.guiMaster.movedPopup) {
              this.guiMaster.movedPopup.onMouseMove(event);
            }
          }
        };

        scope.onMouseDownOnBox = function(box, event) {
          event.stopPropagation();
          addLogicalMousePosition(event);
          scope.selection.remove();
          if (scope.selectedBoxIds.indexOf(box.instance.id) === -1) {
            if (!event.ctrlKey) {
              scope.selectedBoxIds = [];
            }
            scope.selectBox(box.instance.id);
            scope.guiMaster.movedBoxes = [box];
            scope.guiMaster.movedBoxes[0].onMouseDown(event);
          } else if (event.ctrlKey) {
            var selectedIndex = scope.selectedBoxIds.indexOf(box.instance.id);
            scope.selectedBoxIds.splice(selectedIndex, selectedIndex);
            scope.guiMaster.movedBoxes[0].onMouseDown(event);
          } else {
            scope.guiMaster.movedBoxes = this.selectedBoxes();
            scope.guiMaster.movedBoxes.map(function(b) {
              b.onMouseDown(event);
            });
          }
        };

        scope.onMouseUpOnBox = function(box, event) {
          if (box.isMoved || scope.pulledPlug) {
            return;
          }
          var leftButton = event.button === 0;
          if (!leftButton || event.ctrlKey || event.shiftKey) {
            return;
          }
          var model = new PopupModel(
            box.instance.id,
            box.instance.operationID,
            {
              type: 'box',
              boxId: box.instance.id,
            },
            event.pageX - 200,
            event.pageY + 60,
            500,
            500,
            scope.guiMaster);
          model.toggle();
        };

        scope.closePopup = function(id) {
          for (var i = 0; i < scope.guiMaster.popups.length; ++i) {
            if (scope.guiMaster.popups[i].id === id) {
              scope.guiMaster.popups.splice(i, 1);
              return true;
            }
          }
          return false;
        };

        scope.onClickOnPlug = function(plug, event) {
          var leftButton = event.button === 0;
          if (!leftButton || event.ctrlKey || event.shiftKey) {
            return;
          }
          event.stopPropagation();
          if (plug.direction === 'outputs') {
            var model = new PopupModel(
              plug.boxId + '_' + plug.id,
              plug.boxInstance.operationID + ' ➡ ' + plug.id,
              {
                type: 'plug',
                boxId: plug.boxId,
                plugId: plug.id,
              },
              event.pageX - 300,
              event.pageY + 15,
              500,
              500,
              scope.guiMaster);
            model.toggle();
          }
        };

        scope.onMouseDownOnPlug = function(plug, event) {
          event.stopPropagation();
          scope.pulledPlug = plug;
          scope.mouseLogical = undefined;
        };

        scope.onMouseUpOnPlug = function(plug, event) {
          event.stopPropagation();
          if (scope.pulledPlug) {
            var otherPlug = scope.pulledPlug;
            scope.pulledPlug = undefined;
            scope.guiMaster.wrapper.addArrow(otherPlug, plug);
          }
        };

        scope.onMouseUp = function(event) {
          element[0].style.cursor = '';
          workspaceDrag = false;
          selectBoxes = false;
          scope.selection.remove();
          addLogicalMousePosition(event);
          if (scope.guiMaster.movedBoxes) {
            scope.guiMaster.wrapper.saveIfBoxesMoved();
          }
          scope.guiMaster.movedBoxes = undefined;
          scope.pulledPlug = undefined;
          scope.guiMaster.movedPopup = undefined;
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
            scope.selectedBoxIds = [];
            scope.selection.onMouseDown(event);
          }
        };

        scope.workspaceTransform = function() {
          var z = zoomToScale(workspaceZoom);
          return 'translate(' + workspaceX + ', ' + workspaceY + ') scale(' + z + ')';
        };

        scope.boxes = function() {
          return scope.guiMaster && scope.guiMaster.wrapper ? this.guiMaster.wrapper.boxes : [];
        };

        scope.arrows = function() {
          return scope.guiMaster && scope.guiMaster.wrapper ? this.guiMaster.wrapper.arrows : [];
        };

        scope.selectBoxesInSelection = function() {
          var boxes = this.boxes();
          this.selectedBoxIds = [];
          for (var i = 0; i < boxes.length; i++) {
            var box = boxes[i];
            if (this.selection.inSelection(box)) {
              this.selectedBoxIds.push(box.instance.id);
            }
          }
        };

        scope.selectBox = function(boxId) {
          scope.selectedBoxIds.push(boxId);
        };

        scope.selectedBoxes = function() {
          if (scope.selectedBoxIds) {
            var workspaceWrapper = scope.guiMaster.wrapper;
            return scope.selectedBoxIds.map(function(id) {
              return workspaceWrapper.boxMap[id];
            });
          } else {
            return undefined;
          }
        };

        scope.copyBoxes = function() {
          this.clipboard = angular.copy(this.selectedBoxes());
        };

        scope.pasteBoxes = function(currentPosition) {
          this.guiMaster.wrapper.pasteFromClipboard(this.clipboard, currentPosition);
        };

        scope.deleteBoxes = function(boxIds) {
          var that = this;
          var popups = this.guiMaster.popups.slice();
          popups.forEach(function(popup) {
            var boxId = popup.content.boxId;
            if (boxIds.includes(boxId) && boxId !== 'anchor') {
              that.closePopup(popup.id);
            }
          });
          this.guiMaster.wrapper.deleteBoxes(boxIds);
        };

        scope.deleteSelectedBoxes = function() {
          this.deleteBoxes(this.selectedBoxIds);
          this.selectedBoxIds = [];
        };


        var hk = hotkeys.bindTo(scope);
        hk.add({
          combo: 'ctrl+c', description: 'Copy boxes',
          callback: function() { scope.copyBoxes(); } });
        hk.add({
          combo: 'ctrl+v', description: 'Paste boxes',
          callback: function() {
            scope.pasteBoxes(addLogicalMousePosition({ pageX: 0, pageY: 0}));
          } });
        hk.add({
          combo: 'del', description: 'Paste boxes',
          callback: function() { scope.deleteSelectedBoxes(); } });

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
            scope.guiMaster.wrapper.addBox(operationID, origEvent, boxID);
          });
        });

        scope.$on('$destroy', function() {
          scope.guiMaster.stopProgressUpdate();
        });
      }
    };
  });
