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

angular.module('biggraph')
  .factory('workspace', function(workspaceWrapper, util, $interval) {
    return function(boxCatalog, workspaceName) {
      var progressUpdater;

      var boxCatalogMap = {};
      for (var i = 0; i < boxCatalog.boxes.length; ++i) {
        var boxMeta = boxCatalog.boxes[i];
        boxCatalogMap[boxMeta.operationID] = boxMeta;
      }

      var workspace = {
        name: workspaceName,

        boxes: function() {
          return this.wrapper ? this.wrapper.boxes : [];
        },

        arrows: function() {
          return this.wrapper ? this.wrapper.arrows : [];
        },

        selection: {
          startX: undefined,
          startY: undefined,
          endX: undefined,
          endY: undefined,
          // The parameters below are calculated from the above ones by this.updateSelection.
          leftX: undefined,
          upperY: undefined,
          width: undefined,
          height: undefined
        },

        updateSelection: function(){
          this.selection.leftX = Math.min(this.selection.startX, this.selection.endX);
          this.selection.upperY = Math.min(this.selection.startY, this.selection.endY);
          this.selection.width = Math.abs(this.selection.endX - this.selection.startX);
          this.selection.height = Math.abs(this.selection.endY - this.selection.startY);
        },

        removeSelection: function(){
          this.selection.startX = undefined;
          this.selection.endX = undefined;
          this.selection.startY = undefined;
          this.selection.endY = undefined;
          this.selection.leftX = undefined;
          this.selection.upperY = undefined;
          this.selection.width = undefined;
          this.selection.length = undefined;
        },

        selectedBoxIds: [],

        loadWorkspace: function() {
          var that = this;
          util.nocache(
            '/ajax/getWorkspace',
            {
              name: this.name
            })
            .then(function(response) {
              var state = response.workspace;
              that.backendState = state;
              // User edits will be applied to a deep copy of
              // the original backend state. This way watchers
              // of backendState will only be notified once the
              // backend is fully aware of the new state.
              var stateCopy = angular.copy(state);
              that.wrapper = workspaceWrapper(
                stateCopy, boxCatalogMap);
              that.wrapper.assignStateInfoToPlugs(response.outputs);
            })
            .then(function() {
              that.startProgressUpdate();
            });
        },

        saveWorkspace: function() {
          var that = this;
          util.post(
            '/ajax/setWorkspace',
            {
              name: this.name,
              workspace: that.wrapper.state,
            }).finally(
              // Reload workspace both in error and success cases.
              function() { that.loadWorkspace(); });
        },

        selectBox: function(boxId) {
          this.selectedBoxIds.push(boxId);
        },

        selectedBoxes: function() {
          if (this.selectedBoxIds) {
            var workspaceWrapper = this.wrapper;
            return this.selectedBoxIds.map(function(id){return workspaceWrapper.boxMap[id];});
          } else {
            return undefined;
          }
        },

        getBox: function(id) {
          return this.wrapper.boxMap[id];
        },

        updateBox: function(id, paramValues, parametricParameters) {
          var box = this.getBox(id).instance;
          if (!angular.equals(paramValues, box.parameters)) {
            this.wrapper.setBoxParams(id, paramValues, parametricParameters);
            this.saveWorkspace();
          }
        },

        selectBoxesInSelection: function(){
          var boxes = this.boxes();
          this.selectedBoxIds = [];
          for (var i = 0; i < boxes.length; i++) {
            var box = boxes[i];
            if(this.inSelection(box)){
              this.selectedBoxIds.push(box.instance.id);
            }
          }
        },

        inSelection: function(box){
          var sb = this.selection;
          return(sb.leftX < box.instance.x + box.width &&
            box.instance.x < sb.leftX + sb.width &&
            sb.upperY < box.instance.y + box.height &&
            box.instance.y < sb.upperY + sb.height);
        },

        selectState: function(boxID, outputID) {
          var outPlug = this.wrapper.boxMap[boxID].outputMap[outputID];
          this.selectedStateId = outPlug.stateID;
          this.selectedStateKind = outPlug.kind;
        },

        selectPlug: function(plug) {
          this.selectedPlug = plug;
          if (plug.direction === 'outputs') {
            this.selectState(plug.boxId, plug.id);
          } else {
            this.selectedState = undefined;
          }
        },

        onMouseMove: function(mouseLogical) {
          this.mouseLogical = mouseLogical;
          if (event.buttons === 1 && this.movedBoxes) {
            for(i = 0; i < this.movedBoxes.length; i++){
              this.movedBoxes[i].onMouseMove(this.mouseLogical);
            }
          }
        },

        onMouseUp: function() {
          if(this.movedBoxes){
            for(i = 0; i < this.movedBoxes.length; i++){
              if (this.movedBoxes[i].isMoved) {
                this.saveWorkspace();
                break;
              }
            }
          }
          this.movedBoxes = undefined;
          this.pulledPlug = undefined;
        },

        onMouseDownOnBox: function(box, mouseLogical) {
          var selectedBoxes = this.selectedBoxes();
          if (selectedBoxes.indexOf(box) === -1) {
            this.selectedBoxIds = [];
            this.selectBox(box.instance.id);
            this.movedBoxes = [box];
            this.movedBoxes[0].onMouseDown(mouseLogical);
          } else {
            this.movedBoxes = selectedBoxes;
            this.movedBoxes.map(function(b) {
              b.onMouseDown(mouseLogical);});
          }
        },

        onMouseDownOnPlug: function(plug, event) {
          event.stopPropagation();
          this.pulledPlug = plug;
        },

        onMouseUpOnPlug: function(plug, event) {
          event.stopPropagation();
          if (this.pulledPlug) {
            var otherPlug = this.pulledPlug;
            this.pulledPlug = undefined;
            if (this.wrapper.addArrow(otherPlug, plug)) {
              this.saveWorkspace();
            }
          }
          if (!this.pulledPlug || this.pulledPlug !== plug) {
            this.selectPlug(plug);
          }
        },

        // boxID should be used for test-purposes only
        addBox: function(operationId, pos, boxID) {
          var box = this.wrapper.addBox(operationId, pos.x, pos.y, boxID);
          this.saveWorkspace();
          return box;
        },

        clipboard: [],

        copyBoxes: function() {
          this.clipboard = angular.copy(this.selectedBoxes());
        },

        pasteBoxes: function() {
          var clipboard = this.clipboard;
          var mapping = {};
          for (var i = 0; i < clipboard.length; ++i) {
            var box = clipboard[i].instance;
            var diffX = clipboard[i].width;
            var createdBox =  this.addBox(
              box.operationID, {x: box.x + 1.1 * diffX, y: box.y + 10});
            mapping[box.id] = createdBox;
          }
          for (i = 0; i < clipboard.length; ++i) {
            var oldBox = clipboard[i].instance;
            var newBox = mapping[oldBox.id];
            for (var key in oldBox.inputs) {
              var oldInputId = oldBox.inputs[key].boxID;
              if (oldInputId in mapping) {
                var newInput = mapping[oldInputId];
                newBox.inputs[key] = { boxID: newInput.id, id: key };
              }
            }
          }
        },

        deleteBoxes: function(boxIds) {
          for(i = 0; i < boxIds.length; i+=1) {
            if (boxIds[i] === 'anchor') {
              util.error('Anchor box cannot be deleted.');
            } else {
              this.wrapper.deleteBox(boxIds[i]);
            }
          }
          this.saveWorkspace();
        },

        deleteSelectedBoxes: function() {
          this.deleteBoxes(this.selectedBoxIds);
          this.selectedBoxIds = [];
        },

        getAndUpdateProgress: function(errorHandler) {
          var wrapperBefore = this.wrapper;
          var that = this;
          if (wrapperBefore) {
            util.nocache('/ajax/getProgress', {
              stateIDs: wrapperBefore.knownStateIDs,
            }).then(
              function success(response) {
                if (that.wrapper && that.wrapper === wrapperBefore) {
                  var progressMap = response.progress;
                  that.wrapper.updateProgress(progressMap);
                }
              },
              errorHandler);
          }
        },

        startProgressUpdate: function() {
          this.stopProgressUpdate();
          var that = this;
          progressUpdater = $interval(function() {
            function errorHandler(error) {
              util.error('Couldn\'t get progress information.', error);
              that.stopProgressUpdate();
              that.wrapper.clearProgress();
            }
            that.getAndUpdateProgress(errorHandler);
          }, 2000);
        },

        stopProgressUpdate: function() {
          if (progressUpdater) {
            $interval.cancel(progressUpdater);
            progressUpdater = undefined;
          }
        },
      };

      workspace.loadWorkspace();
      return workspace;
    };
  });
