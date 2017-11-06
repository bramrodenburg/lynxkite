'use strict';

// The drawing board where the user can create and modify a boxes and
// arrows diagram.

angular.module('biggraph')
  .directive(
  'workspaceDrawingBoard',
  function(
    environment, hotkeys, PopupModel, SelectionModel, WorkspaceWrapper, $rootScope, $q,
    $location, util) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/workspace-drawing-board.html',
      scope: {
        boxCatalog: '=',
        workspaceName: '=',
      },
      link: function(scope, element) {
        scope.workspace = undefined;
        scope.selection = new SelectionModel();
        scope.dragMode = window.localStorage.getItem('drag_mode') || 'pan';
        scope.selectedBoxIds = [];
        scope.movedBoxes = undefined;
        // If the user is connecting plugs by drawing a line with the
        // mouse, then this points to the plug where the line was
        // started.
        scope.pulledPlug = undefined;
        // The last known position of the mouse, expressed in logical
        // workspace coordinates.
        scope.mouseLogical = { x: 300, y: 300 };
        scope.popups = [];
        scope.movedPopup = undefined;

        scope.$watch(
          'workspaceName',

          function() {
            if (scope.workspaceName) {
              scope.workspace = new WorkspaceWrapper(
                scope.workspaceName,
                scope.boxCatalog);
              scope.workspace.loadWorkspace();
            }
          });
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
        var mouseX = 300;
        var mouseY = 300;
        var svgElement = element.find('svg');
        var svgOffset = svgElement.offset();
        function zoomToScale(z) { return Math.exp(z * 0.001); }
        function addLogicalMousePosition(event) {
          // event.offsetX/Y are distorted when the mouse is
          // over a popup window (even if over an invisible
          // overflow part of it), hence we compute our own:
          event.workspaceX = event.pageX - svgOffset.left;
          event.workspaceY = event.pageY - svgOffset.top;
          // Add location according to pan and zoom:
          var logical = scope.pageToLogical({ x: event.pageX, y: event.pageY });
          event.logicalX = logical.x;
          event.logicalY = logical.y;
          return event;
        }

        scope.pageToLogical = function(pos) {
          var z = zoomToScale(workspaceZoom);
          return {
            x: (pos.x - svgOffset.left - workspaceX) / z,
            y: (pos.y - svgOffset.top - workspaceY) / z,
          };
        };

        scope.logicalToPage = function(pos) {
          var z = zoomToScale(workspaceZoom);
          return {
            x: pos.x * z + workspaceX + svgOffset.left,
            y: pos.y * z + workspaceY + svgOffset.top,
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
          svgOffset = svgElement.offset(); // Just in case the layout changed.
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
          if (leftButton || environment.protractor) {
            scope.mouseLogical = {
              x: event.logicalX,
              y: event.logicalY,
            };
            if (scope.movedBoxes) {
              for (var i = 0; i < scope.movedBoxes.length; i++) {
                scope.movedBoxes[i].onMouseMove(event);
              }
              if (scope.movedBoxes.length === 1) {
                autoConnect(scope.movedBoxes[0]);
              }
            } else if (scope.movedPopup) {
              scope.movedPopup.onMouseMove(event);
            }
          }
        };

        // Tries hooking up plugs when a box is moving.
        function autoConnect(moving) {
          function flatten(array) {
            return Array.prototype.concat.apply([], array);
          }
          function filterOpen(plugs) {
            return plugs.filter(function(plug) { return plug.getAttachedPlugs().length === 0; });
          }
          var allOutputs = flatten(scope.workspace.boxes.map(function(box) { return box.outputs; }));
          var allInputs = flatten(scope.workspace.boxes.map(function(box) { return box.inputs; }));
          autoConnectPlugs(moving.inputs, allOutputs);
          autoConnectPlugs(filterOpen(moving.outputs), filterOpen(allInputs));
        }

        function autoConnectPlugs(srcPlugs, dstPlugs) {
          var hookDistance = 20;
          for (var i = 0; i < srcPlugs.length; ++i) {
            var src = srcPlugs[i];
            for (var j = 0; j < dstPlugs.length; ++j) {
              var dst = dstPlugs[j];
              var dx = src.cx() - dst.cx();
              var dy = src.cy() - dst.cy();
              var dist = Math.sqrt(dx * dx + dy * dy);
              if (dist < hookDistance) {
                scope.workspace.addArrow(src, dst, { willSaveLater: true });
              }
            }
          }
        }

        scope.onMouseDownOnBox = function(box, event) {
          event.stopPropagation();
          var leftClick = event.button === 0;
          if (!leftClick) {
            return;
          }
          addDragListeners();

          addLogicalMousePosition(event);
          scope.selection.remove();
          if (scope.selectedBoxIds.indexOf(box.instance.id) === -1) {
            if (!event.ctrlKey) {
              scope.selectedBoxIds = [];
            }
            scope.selectBox(box.instance.id);
            scope.movedBoxes = [box];
            scope.movedBoxes[0].onMouseDown(event);
          } else if (event.ctrlKey) {
            var selectedIndex = scope.selectedBoxIds.indexOf(box.instance.id);
            scope.selectedBoxIds.splice(selectedIndex, 1);
            scope.movedBoxes[0].onMouseDown(event);
          } else {
            scope.movedBoxes = this.selectedBoxes();
            scope.movedBoxes.map(function(b) {
              b.onMouseDown(event);
            });
          }
        };

        function placePopup(event) {
          // Avoid the event position, stay on the screen, and try to be close to the event.
          var w = 500;
          var h = 500;
          var eventX = event.pageX - w / 2;
          var eventY = event.pageY - h / 2;
          var minX = 0;
          var minY = svgOffset.top;  // Do not overlap toolbar.
          var maxX = svgElement.width() - w - 35;  // Do not overlap toolbox.
          var maxY = svgElement.height() - h;

          function len(x, y) { return Math.sqrt(x * x + y * y); }
          function rectangleOverlapArea(left1, top1, width1, height1, left2, top2, width2, height2) {
            var right1 = left1 + width1;
            var bottom1 = top1 + height1;
            var right2 = left2 + width2;
            var bottom2 = top2 + height2;
            // Comute intersection:
            var left = Math.max(left1, left2);
            var top = Math.max(top1, top2);
            var right = Math.min(right1, right2);
            var bottom = Math.min(bottom1, bottom2);
            if (left > right || top > bottom) {
              return 0;
            } else {
              return (right - left) * (bottom - top);
            }
          }
          function overlap(x, y) {
            var total = 0;
            for (var i = 0; i < scope.popups.length; ++i) {
              total += rectangleOverlapArea(
                  scope.popups[i].x, scope.popups[i].y, scope.popups[i].width, scope.popups[i].height,
                  x, y, w, h);
            }
            return total;
          }
          function score(x, y) {
            return {
              distance: len(x - eventX, y - eventY),
              overlap: overlap(x, y),
            };
          }
          function isScoreBetterThan(current, best) {
            var minDist = Math.sqrt(w * w + h * h) / 2;
            if (best.distance < minDist && current.distance > best.distance) {
              return true;
            }
            if (current.overlap < best.overlap) {
              return true;
            } else if (current.overlap > best.overlap) {
              return false;
            }
            if (current.distance > minDist && current.distance < best.distance) {
              return true;
            } else {
              return false;
            }
          }

          var bestX = (minX + maxX) / 2;
          var bestY = (minY + maxY) / 2;
          var bestScore = score(bestX, bestY);
          for (var x = minX; x <= maxX; x += (maxX - minX) / 10) {
            for (var y = minY; y <= maxY; y += (maxY - minY) / 20) {
              var currentScore = score(x, y);
              if (isScoreBetterThan(currentScore, bestScore)) {
                bestX = x;
                bestY = y;
                bestScore = currentScore;
              }
            }
          }
          return {
            x: bestX,
            y: bestY,
            width: w,
            height: h,
          };
        }

        scope.onMouseUpOnBox = function(box, event) {
          if (box.isDirty || scope.pulledPlug || scope.selection.isActive()) {
            return;
          }
          var leftButton = event.button === 0;
          if (!leftButton || event.ctrlKey || event.shiftKey) {
            return;
          }
          var pos = placePopup(event);
          var model = new PopupModel(
            box.instance.id,
            box.instance.operationId,
            {
              type: 'box',
              boxId: box.instance.id,
            },
            pos.x,
            pos.y,
            pos.width,
            pos.height,
            scope);
          model.toggle();
        };

        scope.closePopup = function(id) {
          for (var i = 0; i < scope.popups.length; ++i) {
            if (scope.popups[i].id === id) {
              scope.popups.splice(i, 1);
              return true;
            }
          }
          return false;
        };

        scope.closeLastPopup = function() {
          scope.popups.pop();
        };

        scope.onClickOnPlug = function(plug, event) {
          var leftButton = event.button === 0;
          if (!leftButton || event.ctrlKey || event.shiftKey) {
            return;
          }
          event.stopPropagation();
          if (plug.direction === 'outputs') {
            var pos = placePopup(event);
            var model = new PopupModel(
              plug.boxId + '_' + plug.id,
              plug.boxInstance.operationId + ' ➡ ' + plug.id,
              {
                type: 'plug',
                boxId: plug.boxId,
                plugId: plug.id,
              },
              pos.x,
              pos.y,
              pos.width,
              pos.height,
              scope);
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
            scope.workspace.addArrow(otherPlug, plug);
          }
        };

        scope.onMouseUp = function() {
          element[0].style.cursor = '';
          workspaceDrag = false;
          selectBoxes = false;
          removeDragListeners();
          scope.selection.remove();
          if (scope.movedBoxes) {
            scope.workspace.saveIfBoxesDirty();
          }
          scope.movedBoxes = undefined;
          scope.pulledPlug = undefined;
          scope.movedPopup = undefined;
        };

        function wrapCallback(callback) {
          return function(event) { scope.$apply(function () { callback(event); }); };
        }
        var wrappedOnMouseMove = wrapCallback(scope.onMouseMove);
        var wrappedOnMouseUp = wrapCallback(scope.onMouseUp);

        scope.startMovingPopup = function(popup) {
          scope.movedPopup = popup;
          addDragListeners();
        };
        function addDragListeners() {
          window.addEventListener('mousemove', wrappedOnMouseMove);
          window.addEventListener('mouseup', wrappedOnMouseUp);
        }
        function removeDragListeners() {
          window.removeEventListener('mousemove', wrappedOnMouseMove);
          window.removeEventListener('mouseup', wrappedOnMouseUp);
        }

        scope.onMouseDown = function(event) {
          addDragListeners();
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
          if (!scope.workspace) {
            return undefined;
          }
          var boxes = scope.workspace.boxes.slice();
          boxes.sort(function(a, b) { return a.instance.y < b.instance.y ? -1 : 1; });
          return boxes;
        };

        scope.arrows = function() {
          return scope.workspace ? scope.workspace.arrows : [];
        };

        scope.selectBoxesInSelection = function() {
          var boxes = scope.workspace.boxes;
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
            return scope.selectedBoxIds.map(function(id) {
              return scope.workspace.boxMap[id];
            });
          } else {
            return undefined;
          }
        };

        scope.drawingBoardHasFocus = function() {
          // The svg tag cannot be focused apparently, but we don't want to hijack copy and paste
          // events from things like input fields.
          return document.activeElement.tagName === "BODY";
        };

        /* global jsyaml */
        scope.copyBoxes = function(e) {
          if (!scope.drawingBoardHasFocus() || window.getSelection().toString()) {
            return;
          }
          var data = jsyaml.safeDump(
            scope.selectedBoxes().map(function(box) { return box.instance; }),
            { noCompatMode: true });
          e.clipboardData.setData('text/plain', data);
          e.preventDefault();
        };

        scope.pasteBoxes = function(e) {
          if (!scope.drawingBoardHasFocus()) {
            return;
          }
          var data = e.clipboardData.getData('Text');
          try {
            var boxes = jsyaml.safeLoad(data);
          } catch (err) {
            var jData = { clipboard: data };
            var message = 'Cannot create boxes from clipboard. (Not in JSON format)';
            util.error(message, jData);
            /* eslint-disable no-console */
            console.error(message, err);
            return;
          }
          try {
            var added = scope.workspace.pasteFromData(boxes, getMouseLogical(-50, -50));
          } catch (err) {
            var someJson = { clipboard: data };
            message = 'Cannot parse boxes from clipboard';
            util.error(message, someJson);
            /* eslint-disable no-console */
            console.error(message, err);
            return;
          }
          scope.selectedBoxIds = added.map(function(box) { return box.id; });
        };

        var wrappedCopyBoxes = wrapCallback(scope.copyBoxes);
        var wrappedPasteBoxes = wrapCallback(scope.pasteBoxes);

        window.addEventListener('copy', wrappedCopyBoxes);
        window.addEventListener('paste', wrappedPasteBoxes);

        scope.deleteBoxes = function(boxIds) {
          var popups = scope.popups.slice();
          popups.forEach(function(popup) {
            var boxId = popup.content.boxId;
            if (boxIds.includes(boxId) && boxId !== 'anchor') {
              scope.closePopup(popup.id);
            }
          });
          scope.workspace.deleteBoxes(boxIds);
        };

        scope.deleteSelectedBoxes = function() {
          this.deleteBoxes(this.selectedBoxIds);
          this.selectedBoxIds = [];
        };

        scope.diveUp = function() {
          var id = scope.workspace.customBoxStack.pop();
          scope.workspace.loadWorkspace();
          scope.popups = [];
          scope.selectedBoxIds = [id];
        };

        scope.diveDown = function() {
          scope.workspace.customBoxStack.push(scope.selectedBoxIds[0]);
          scope.workspace.loadWorkspace();
          scope.popups = [];
          scope.selectedBoxIds = [];
        };

        scope.saveSelectionAsCustomBox = function(name, success, error) {
          var b = scope.workspace.saveAsCustomBox(
              scope.selectedBoxIds, name, 'Created from ' + scope.workspaceName);
          scope.selectedBoxIds = [b.customBox.id];
          b.promise.then(success, error);
        };
        var hk = hotkeys.bindTo(scope);
        hk.add({
          // Only here for the tooltip.
          combo: 'mod+c', description: 'Copy boxes', callback: function() {} });
        hk.add({
          // Only here for the tooltip.
          combo: 'mod+v', description: 'Paste boxes', callback: function() {} });
        hk.add({
          combo: 'mod+z', description: 'Undo',
          callback: function() { scope.workspace.undo(); } });
        hk.add({
          combo: 'mod+y', description: 'Redo',
          callback: function() { scope.workspace.redo(); } });
        hk.add({
          combo: 'del', description: 'Delete boxes',
          callback: function() { scope.deleteSelectedBoxes(); } });
        hk.add({
          combo: 'backspace', description: 'Delete boxes',
          callback: function() { scope.deleteSelectedBoxes(); } });
        hk.add({
          combo: '/', description: 'Find operation',
          callback: function(e) {
            e.preventDefault();  // Do not type "/".
            $rootScope.$broadcast('open operation search');
          }});
        hk.add({
          combo: 'escape', description: 'Close last popup',
          callback: function() {
            scope.closeLastPopup();
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
            // Enforce a maximal zoom where the icons are not yet pixelated.
            workspaceZoom = Math.min(workspaceZoom, 1000 * Math.log(4 / 3));
            // Enforce a minimal zoom where boxes are still visible.
            workspaceZoom = Math.max(workspaceZoom, 1000 * Math.log(1 / 50));
            var z2 = zoomToScale(workspaceZoom);
            // Maintain screen-coordinates of logical point under the mouse.
            workspaceX = mouseX - (mouseX - workspaceX) * z2 / z1;
            workspaceY = mouseY - (mouseY - workspaceY) * z2 / z1;
          });
        });

        scope.addOperation = function(op, event) {
          addLogicalMousePosition(event);
          // Offset event to place icon centered on the cursor.
          // TODO: May be better to center all icons on the logical positions.
          // Then we never need to worry about sizes.
          event.logicalX -= 50;
          event.logicalY -= 50;
          var box = scope.workspace.addBox(op.operationId, event, { willSaveLater: true });
          scope.onMouseDownOnBox(scope.workspace.getBox(box.id), event);
        };

        // Insert an import box when a file is dropped on the board.
        element.bind('dragover', function(e) { e.preventDefault(); });
        element.bind('drop', function(event) {
          event = event.originalEvent;
          event.preventDefault();
          addLogicalMousePosition(event);
          event.logicalX -= 50;
          event.logicalY -= 50;
          var file = event.dataTransfer.files[0];
          var op = 'Import CSV';
          if (file.name.match(/\.json$/i)) {
            op = 'Import JSON';
          } else if (file.name.match(/\.parquet$/i)) {
            op = 'Import Parquet';
          } else if (file.name.match(/\.orc$/i)) {
            op = 'Import ORC';
          }
          var box = scope.workspace.addBox(op, event, { willSaveLater: true });
          if (op === 'Import CSV') {
            box.parameters.infer = 'yes';
          }
          uploadFile(file).then(function(filename) {
            box.parameters.filename = filename;
            return util.post('/ajax/importBox', box);
          }).then(function(response) {
            box.parameters.imported_table = response.guid;
            box.parameters.last_settings = response.parameterSettings;
            scope.workspace.saveWorkspace();
          }).catch(function() {
            scope.workspace.deleteBoxes([box.id]);
          });
        });

        function uploadFile(file) {
          var defer = $q.defer();
          var xhr = new XMLHttpRequest();
          xhr.open('POST', '/ajax/upload');
          xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) {  // DONE
              scope.$apply(function() {
                if (xhr.status === 200) {  // SUCCESS
                  defer.resolve(xhr.responseText);
                } else {
                  util.error('File upload failed.', { file: file });
                  defer.reject(xhr);
                }
              });
            }
          };
          var fd = new FormData();
          fd.append('file', file);
          xhr.send(fd);
          return defer.promise;
        }

        scope.$on('$destroy', function() {
          scope.workspace.stopProgressUpdate();
          window.removeEventListener('copy', wrappedCopyBoxes);
          window.removeEventListener('paste', wrappedPasteBoxes);
        });

        // Box colors come as strings from the backend. We map them to feColorMatrix matrices that
        // get applied to the grayscale icon images. This function builds the mapping.
        function makeFilters() {
          // Brand colors.
          var colors = [
            ['blue', '#39bcf3'],
            ['green', '#6fdb11'],
            ['orange', '#ff8800'],
            ['purple', '#bf45e8'],
          ];
          var filters = {};
          for (var i = 0; i < colors.length; ++i) {
            var name = colors[i][0];
            /* global tinycolor */
            var c = tinycolor(colors[i][1]).toRgb();
            filters[name] = (
                (c.r / 255 / 2.3 + ' ').repeat(3) + '0 0 ' +
                (c.g / 255 / 2.3 + ' ').repeat(3) + '0 0 ' +
                (c.b / 255 / 2.3 + ' ').repeat(3) + '0 0   0 0 0 1 0');
          }
          // The "natural" filter leaves the colors alone. This is used for user-specified images.
          filters.natural = '1 0 0 0 0   0 1 0 0 0   0 0 1 0 0   0 0 0 1 0';
          return filters;
        }
        scope.filters = makeFilters();

        scope.bezier = function(x1, y1, x2, y2) {
          return ['M', x1, y1, 'C', x1 + 100, y1, ',', x2 - 100, y2, ',', x2, y2].join(' ');
        };

        scope.getDirectoryPart = function(path) {
          if (path === undefined) { return undefined; }
          var dir = path.split('/').slice(0, -1);
          return dir.length === 0 ? '' : dir.join('/') + '/';
        };

        scope.getLastPart = function(path) {
          if (path === undefined) { return undefined; }
          return path.split('/').slice(-1)[0];
        };

        scope.closeWorkspace = function() {
          $location.url('/');
        };

        scope.$on('create box under mouse', createBoxUnderMouse);
        function createBoxUnderMouse(event, operationId) {
          addAndSelectBox(operationId, getMouseLogical(-50, -50));
        }

        // This is separate from scope.addOperation because we don't have a mouse event here,
        // which makes using the onMouseDown function pretty difficult.
        function addAndSelectBox(id, pos, options) {
          var box = scope.workspace.addBox(id, { logicalX: pos.x, logicalY: pos.y }, options);
          scope.selectedBoxIds = [box.id];
        }

        function getMouseLogical(offx, offy) {
          var z = zoomToScale(workspaceZoom);
          return { x: (mouseX - workspaceX) / z + offx, y: (mouseY - workspaceY) / z + offy };
        }

        scope.deleteArrow = function(arrow) {
          scope.workspace.deleteArrow(arrow);
        };
      }
    };
  });
