'use strict';

// Creates a workspace object. A workspace is a set of boxes and arrows.
// This object wraps the actual workspace data representation and
// provides methods to interact with the GUI, e.g. mouse events
// and values to bind with Angular to SVG elements.
//
// Every time the underlying workspace data was significantly changed, the
// _build() method is invoked to rebuild the frontend-facing data.
// The flow of changes here is always one-way:
// 1. user change or new workspace state loaded
// 2. "raw" state is updated
// 3. _build() updates the frontend-facing objects

angular.module('biggraph').factory('workspaceWrapper', function(boxWrapper) {
  return function(state, boxCatalogMap) {
    var wrapper = {
      state: state,
      // The below data structures are generated from rawBoxes
      // by this.build(). These are the ones that interact with
      // the GUI.
      boxes: [],
      arrows: [],
      boxMap: {},

      _buildBoxes: function() {
        this.boxes = [];
        this.boxMap = {};
        var box;
        for (var i = 0; i < this.state.boxes.length; ++i) {
          var rawBox = this.state.boxes[i];
          var operationId = rawBox.operationID;
          var boxId = rawBox.id;
          box = boxWrapper(boxCatalogMap[operationId], rawBox);
          this.boxes[i] = box;
          this.boxMap[boxId] = box;
        }
      },

      _lookupArrowEndpoint: function(list, id) {
        for (var i = 0; i < list.length; ++i) {
          if (list[i].id === id) {
            return list[i];
          }
        }
        return undefined;
      },

      _createArrow: function(srcPlug, dstPlug) {
        return {
          x1: srcPlug.x,
          y1: srcPlug.y,
          x2: dstPlug.x,
          y2: dstPlug.y,
        };
      },

      _buildArrows: function() {
        this.arrows = [];
        for (var i = 0; i < this.boxes.length; ++i) {
          var dst = this.boxes[i];
          var inputs = dst.instance.inputs;
          for (var inputName in inputs) {
            if (inputs.hasOwnProperty(inputName)) {
              var input = inputs[inputName];
              var src = this.boxMap[input.boxID];
              if(src){
                var srcPlug = this._lookupArrowEndpoint(
                  src.outputs, input.id);
                var dstPlug = this._lookupArrowEndpoint(
                  dst.inputs, inputName);
                this.arrows.push(this._createArrow(
                  srcPlug, dstPlug));
              }
            }
          }
        }
      },

      // If state was updated, this needs to run so that the frontend-facing objects
      // are also updated.
      _build: function() {
        this._buildBoxes();
        this._buildArrows();
      },

      // boxID should be used for test-purposes only
      addBox: function(operationId, x, y, boxId) {
        var usedIds = this.state.boxes.map(function(box) {
            return box.id;
          });
        var cnt = 1;
        while(usedIds.includes(operationId.replace(/ /g, '-') + '_' + cnt)) {
          cnt += 1;
        }
        boxId = boxId || operationId.replace(/ /g, '-') + '_' + cnt;
        this.state.boxes.push(
            {
              id: boxId,
              operationID: operationId,
              x: x,
              y: y,
              inputs: {},
              parameters: {},
              parametricParameters: {}
            });
        this._build();
      },

      deleteBox: function(boxId) {
        this.state.boxes = this.state.boxes.filter(function(box) {
          return box.id !== boxId;
        });
        this.state.boxes.map(function(box) {
          var inputs = box.inputs;
          for (var inputName in inputs) {
            if (inputs[inputName].boxID === boxId) {
              delete box.inputs[inputName];
            }
          }
        });
        this._build();
      },

      addArrow: function(plug1, plug2) {
        if (plug1.direction === plug2.direction) {
          return false;
        }
        var plugs = {};
        plugs[plug1.direction] = plug1;
        plugs[plug2.direction] = plug2;
        var src = plugs.outputs;
        var dst = plugs.inputs;

        // Mutate raw workflow:
        dst.boxInstance.inputs[dst.id] = {
          boxID: src.boxId,
          id: src.id
        };
        // Rebuild API objects based on raw workflow:
        this._build();
        return true;
      },

      assignStateInfoToPlugs: function(stateInfo) {
        this.knownStateIDs = [];
        this.stateID2Plug = {};
        for (var i = 0; i < stateInfo.length; i++) {
          var item = stateInfo[i];
          var boxOutput = item.boxOutput;
          var stateID = item.stateID;
          this.knownStateIDs.push(stateID);
          var box = this.boxMap[boxOutput.boxID];
          var plug = box.outputMap[boxOutput.id];
          plug.stateID = stateID;
          plug.setHealth(item.success);
          plug.kind = item.kind;
          this.stateID2Plug[stateID] = plug;
        }
      },

      updateProgress: function(progressMap) {
        for (var stateID in progressMap) {
          if (progressMap.hasOwnProperty(stateID)) {
            var progress = progressMap[stateID];
            // failed states has 'undefined' as progress
            if (progress) {
              var plug = this.stateID2Plug[stateID];
              if (plug) {
                plug.updateProgress(progress);
              }
            }
          }
        }
      },

      clearProgress: function() {
        for (var i = 0; i < this.boxes.length; i++) {
          var box = this.boxes[i];
          for (var j = 0; j < box.outputs.length; j++) {
            box.outputs[j].clearProgress();
          }
        }
      },

      setBoxParams: function(boxId, paramValues, parametricParameters) {
        var simple = Object.assign({}, paramValues);
        var parametric = {};
        for (var name in simple) {
          if ((name in parametricParameters) && (parametricParameters[name] === true)) {
            parametric[name] = simple[name];
            delete simple[name];
          }
        }

        this.boxMap[boxId].instance.parameters = simple;
        this.boxMap[boxId].instance.parametricParameters = parametric;
      },

    };

    wrapper._build();
    return wrapper;
  };
});
