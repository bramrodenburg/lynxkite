'use strict';

// This class manages a workspace state and its connection to Angular
// components (workspace-drawing-board, box-editor, state-view) and the
// backend.
// Handling the workspace state data structure and wrapping it with API
// objects is outsourced from here to workspaceWrapper.
//
// Life cycle:
// 1. boxCatalog needs to be loaded at all times for things to work
// 2. loadWorkspace()
//    - downloads a workspace state and saves it in backendState
//    - creates a workspaceWrapper using the downloaded state and
//      sets this.wrapper to point to it
//    - visible GUI gets updated based on this.wrapper via
//      workspace-drawing-board
// 3. user edit happens, e.g. box move, add box, or add arrow
//    - this updates the wrapper.state
//    - all frontend-facing objects are updated inside
//      workspaceWrapper
//    - backendState remains unchanged at this point
// 5. saveWorkspace()
// 6. GOTO 2

angular.module('biggraph').factory(
  'workspaceGuiMaster',
  function(workspaceWrapper, PopupModel, environment) {
    return function(boxCatalog, workspaceName) {

      var workspace = {
        popups: [],

        selectedBoxIds: [],

        selectBox: function(boxId) {
          this.selectedBoxIds.push(boxId);
        },

        selectedBoxes: function() {
          if (this.selectedBoxIds) {
            var workspaceWrapper = this.wrapper;
            return this.selectedBoxIds.map(function(id) {
              return workspaceWrapper.boxMap[id];
            });
          } else {
            return undefined;
          }
        },

        getBox: function(id) {
          return this.wrapper.boxMap[id];
        },

        getOutputPlug: function(boxId, plugId) {
          return this.getBox(boxId).outputMap[plugId];
        },

        onMouseMove: function(event) {
          var leftButton = event.buttons & 1;
          // Protractor omits button data from simulated mouse events.
          if (!leftButton && !environment.protractor) {
            // Button is no longer pressed. (It was released outside of the window, for example.)
            this.onMouseUp();
          } else {
            this.mouseLogical = {
              x: event.logicalX,
              y: event.logicalY,
            };
            if (this.movedBoxes) {
              for (var i = 0; i < this.movedBoxes.length; i++) {
                this.movedBoxes[i].onMouseMove(event);
              }
            } else if (this.movedPopup) {
              this.movedPopup.onMouseMove(event);
            }
          }
        },

        onMouseUp: function() {
          if (this.movedBoxes) {
            this.wrapper.saveIfBoxesMoved();
          }
          this.movedBoxes = undefined;
          this.pulledPlug = undefined;
          this.movedPopup = undefined;
        },

        onMouseDownOnBox: function(box, event) {
          var selectedBoxes = this.selectedBoxes();
          if (selectedBoxes.indexOf(box) === -1) {
            if (!event.ctrlKey) {
              this.selectedBoxIds = [];
            }
            this.selectBox(box.instance.id);
            this.movedBoxes = [box];
            this.movedBoxes[0].onMouseDown(event);
          } else if (event.ctrlKey) {
            var selectedIndex = this.selectedBoxIds.indexOf(box.instance.id);
            this.selectedBoxIds.splice(selectedIndex, selectedIndex);
            this.movedBoxes[0].onMouseDown(event);
          } else {
            this.movedBoxes = selectedBoxes;
            this.movedBoxes.map(function(b) {
              b.onMouseDown(event);
            });
          }
        },

        closePopup: function(id) {
          for (var i = 0; i < this.popups.length; ++i) {
            if (this.popups[i].id === id) {
              this.popups.splice(i, 1);
              return true;
            }
          }
          return false;
        },

        onClickOnPlug: function(plug, event) {
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
              this);
            model.toggle();
          }
        },

        onMouseUpOnBox: function(box, event) {
          if (box.isMoved || this.pulledPlug) {
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
            this);
          model.toggle();
        },

        onMouseDownOnPlug: function(plug, event) {
          event.stopPropagation();
          this.pulledPlug = plug;
          this.mouseLogical = undefined;
        },

        onMouseUpOnPlug: function(plug, event) {
          event.stopPropagation();
          if (this.pulledPlug) {
            var otherPlug = this.pulledPlug;
            this.pulledPlug = undefined;
            this.wrapper.addArrow(otherPlug, plug);
          }
        },


      };

      workspace.wrapper = workspaceWrapper(workspaceName, boxCatalog);
      workspace.wrapper.loadWorkspace();
      return workspace;
    };
  });
