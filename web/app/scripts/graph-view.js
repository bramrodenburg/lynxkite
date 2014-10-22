'use strict';

angular.module('biggraph').directive('graphView', function(util) {
  /* global SVG_UTIL, COMMON_UTIL, FORCE_LAYOUT */
  var svg = SVG_UTIL;
  var common = COMMON_UTIL;
  var directive = {
      template: '<svg class="graph-view" version="1.1" xmlns="http://www.w3.org/2000/svg"></svg>',
      scope: { graph: '=', left: '=', right: '=', menu: '=' },
      replace: true,
      link: function(scope, element) {
        var gv = new GraphView(scope, element);
        function updateGraph() {
          if (scope.graph === undefined || !scope.graph.$resolved || !iconsLoaded()) {
            gv.loading();
          } else if (scope.graph.$error) {
            gv.error(scope.graph.$error);
          } else {
            gv.update(scope.graph, scope.menu);
          }
        }
        util.deepWatch(scope, 'graph', updateGraph);
        // It is possible, especially in testing, that we get the graph data faster than the icons.
        // In this case we delay the drawing until the icons are loaded.
        scope.$on('#svg-icons is loaded', updateGraph);
      },
    };

  function iconsLoaded() {
    return angular.element('#svg-icons #circle').length > 0;
  }

  function getIcon(name) {
    if (!name) { name = 'circle'; }
    var circle = angular.element('#svg-icons #circle');
    var cbb = circle[0].getBBox();
    var icon = angular.element('#svg-icons #' + name.toLowerCase());
    if (icon.length === 0) { icon = circle; }
    var bb = icon[0].getBBox();
    var clone = icon.clone();
    // Take the scaling factor from the circle icon.
    clone.scale = 2 / Math.max(cbb.width, cbb.height);
    clone.center = {
      x: bb.x + bb.width / 2,
      y: bb.y + bb.height / 2,
    };
    return clone;
  }

  function Offsetter(xOff, yOff, zoom, menu) {
    this.xOff = xOff;
    this.yOff = yOff;
    this.zoom = zoom;  // Zoom for positions.
    this.thickness = zoom;  // Zoom for radius/width.
    this.menu = menu;
    this.elements = [];
  }
  Offsetter.prototype.rule = function(element) {
    this.elements.push(element);
    var that = this;
    element.offsetter = this;
    element.screenX = function() {
      return element.x * that.zoom + that.xOff;
    };
    element.screenY = function() {
      return element.y * that.zoom + that.yOff;
    };
    element.activateMenu = function(menuData) {
      that.menu.x = element.screenX();
      that.menu.y = element.screenY();
      that.menu.data = menuData;
      that.menu.enabled = true;
    };
    element.reDraw();
  };
  Offsetter.prototype.panTo = function(x, y) {
    this.xOff = x;
    this.yOff = y;
    this.reDraw();
  };
  Offsetter.prototype.reDraw = function() {
    for (var i = 0; i < this.elements.length; ++i) {
      this.elements[i].reDraw();
    }
  };

  function GraphView(scope, element) {
    this.scope = scope;
    this.unregistration = [];  // Cleanup functions to be called before building a new graph.
    this.svg = angular.element(element);
    this.svg.append([
      svg.marker('arrow'),
      svg.marker('arrow-highlight-in'),
      svg.marker('arrow-highlight-out'),
    ]);
    this.root = svg.create('g', {'class': 'root'});
    this.svg.append(this.root);
    // Top-level mouse/touch listeners.
    this.svgMouseDownListeners = [];
    var that = this;
    this.svg.on('mousedown touchstart', function(e) {
      for (var i = 0; i < that.svgMouseDownListeners.length; ++i) {
        that.svgMouseDownListeners[i](e);
      }
    });
    this.svgMouseWheelListeners = [];
    this.svg.on('wheel', function(e) {
      for (var i = 0; i < that.svgMouseWheelListeners.length; ++i) {
        that.svgMouseWheelListeners[i](e);
      }
    });
  }

  GraphView.prototype.clear = function() {
    svg.removeClass(this.svg, 'loading');
    this.root.empty();
    for (var i = 0; i < this.unregistration.length; ++i) {
      this.unregistration[i]();
    }
    this.unregistration = [];
    this.svgMouseDownListeners = [];
    this.svgMouseWheelListeners = [];
  };

  GraphView.prototype.loading = function() {
    svg.addClass(this.svg, 'loading');
  };

  GraphView.prototype.error = function(msg) {
    this.clear();
    var x = this.svg.width() / 2, y = this.svg.height() / 2;
    var text = svg.create('text', {'class': 'error', x: x, y: y, 'text-anchor': 'middle'});
    var maxLength = 100;  // The error message can be very long and SVG does not wrap text.
    for (var i = 0; i < msg.length; i += maxLength) {
      text.append(svg.create('tspan', {x: x, dy: 30}).text(msg.substring(i, i + maxLength)));
    }
    this.root.append(text);
  };

  var graphToSVGRatio = 0.8;  // Leave some margin.
  var UNCOLORED = 'hsl(0,0%,42%)';

  GraphView.prototype.update = function(data, menu) {
    this.clear();
    var zoom = this.svg.height() * graphToSVGRatio;
    var sides = [this.scope.left, this.scope.right];
    this.edgeGroup = svg.create('g', {'class': 'edges'});
    this.vertexGroup = svg.create('g', {'class': 'nodes'});
    this.legend = svg.create('g', {'class': 'legend'});
    this.root.append([this.edgeGroup, this.vertexGroup, this.legend]);
    var oldVertices = this.vertices || [];
    this.vertices = [];  // Sparse, indexed by side.
    var vsIndices = [];  // Packed, indexed by position in the JSON.
    var vsIndex = 0;
    var halfColumnWidth = this.svg.width() / sides.length / 2;
    var i, vs;
    for (i = 0; i < sides.length; ++i) {
      if (sides[i] && sides[i].graphMode) {
        var xMin = (i * 2) * halfColumnWidth;
        var xOff = (i * 2 + 1) * halfColumnWidth;
        var xMax = (i * 2 + 2) * halfColumnWidth;
        var yOff = this.svg.height() / 2;
        var dataVs = data.vertexSets[vsIndex];
        vsIndex += 1;
        var offsetter;
        if (oldVertices[i] && oldVertices[i].mode === dataVs.mode) {
          offsetter = oldVertices[i].offsetter;
          offsetter.inherited = true;
        } else {
          offsetter = new Offsetter(xOff, yOff, zoom, menu);
        }
        if (dataVs.mode === 'sampled') {
          vs = this.addSampledVertices(dataVs, offsetter, sides[i]);
        } else {
          vs = this.addBucketedVertices(dataVs, offsetter, sides[i]);
        }
        vs.offsetter = offsetter;
        vsIndices.push(i);
        this.vertices[i] = vs;
        this.sideMouseBindings(offsetter, xMin, xMax);
      }
    }
    for (i = 0; i < data.edgeBundles.length; ++i) {
      var e = data.edgeBundles[i];
      // Avoid an error with the Grunt test data, which has edges going to the other side
      // even if we only have one side.
      if (e.srcIdx >= vsIndex || e.dstIdx >= vsIndex) { continue; }
      var src = this.vertices[vsIndices[e.srcIdx]];
      var dst = this.vertices[vsIndices[e.dstIdx]];
      var edges = this.addEdges(e.edges, src, dst);
      if (e.srcIdx === e.dstIdx) {
        src.edges = edges;
      }
    }
    for (i = 0; i < this.vertices.length; ++i) {
      vs = this.vertices[i];
      if (vs && vs.mode === 'sampled') {
        var old = oldVertices[i];
        if (old && old.vertexSetId === vs.vertexSetId) {
          copyLayoutAndFreezeOld(old, vs);
        }
        this.initSampled(vs);
        unfreezeAll(vs);
      }
    }
  };

  function copyLayoutAndFreezeOld(from, to) {
    var fromById = {};
    for (var i = 0; i < from.length; ++i) {
      fromById[from[i].id] = from[i];
    }
    for (i = 0; i < to.length; ++i) {
      var v = to[i];
      var fv = fromById[v.id];
      if (fv) {
        v.x = fv.x;
        v.y = fv.y;
        // Copy frozen status, plus add one more freeze.
        v.frozen = fv.frozen + 1;
      }
    }
  }

  function unfreezeAll(vs) {
    for (var i = 0; i < vs.length; ++i) {
      if (vs[i].frozen) {
        vs[i].frozen -= 1;
      }
    }
  }

  function mapByAttr(vs, attr, type) {
    return vs.map(function(v) {
      return v.attrs[attr][type];
    });
  }

  function doubleColorMap(values) {
    var bounds = common.minmax(values);
    var colorMap = {};
    for (var i = 0; i < values.length; ++i) {
      var h = 300 + common.normalize(values[i], bounds) * 120;
      colorMap[values[i]] = 'hsl(' + h + ',50%,42%)';
    }
    return colorMap;
  }

  function stringColorMap(values) {
    var i, set = {};
    for (i = 0; i < values.length; ++i) {
      set[values[i]] = 1;
    }
    var keys = Object.keys(set);
    keys.sort();  // This provides some degree of stability.
    var colorMap = {};
    for (i = 0; i < keys.length; ++i) {
      var h = Math.floor(360 * i / keys.length);
      colorMap[keys[i]] = 'hsl(' + h + ',50%,42%)';
    }
    // Strings that are valid CSS color names will be used as they are.
    // To identify them we have to try setting them as color on a hidden element.
    var colorTester = angular.element('#svg-icons')[0];
    for (i = 0; i < keys.length; ++i) {
      colorTester.style.color = 'transparent';
      colorTester.style.color = keys[i];
      if (colorTester.style.color !== 'transparent') {
        colorMap[keys[i]] = keys[i];
      }
    }
    return colorMap;
  }

  GraphView.prototype.addSampledVertices = function(data, offsetter, side) {
    var vertices = [];
    vertices.side = side;
    vertices.mode = 'sampled';
    vertices.offsetter = offsetter;
    vertices.vertexSetId = side.vertexSet.id;

    var sizeAttr = (side.attrs.size) ? side.attrs.size.id : undefined;
    var sizeMax = 1;
    if (sizeAttr) {
      var vertexSizeBounds = common.minmax(mapByAttr(data.vertices, sizeAttr, 'double'));
      sizeMax = vertexSizeBounds.max;
    }

    var colorAttr = (side.attrs.color) ? side.attrs.color.id : undefined;
    var colorMap;
    if (colorAttr) {
      var s = (offsetter.xOff < this.svg.width() / 2) ? 'left' : 'right';
      if (side.attrs.color.typeName === 'Double') {
        var values = mapByAttr(data.vertices, colorAttr, 'double');
        colorMap = doubleColorMap(values);
        var bounds = common.minmax(values);
        var legendMap = {};
        legendMap['min: ' + bounds.min] = colorMap[bounds.min];
        legendMap['max: ' + bounds.max] = colorMap[bounds.max];
        this.createLegend(legendMap, s); // only shows the min max values
      } else if (side.attrs.color.typeName === 'String') {
        colorMap = stringColorMap(mapByAttr(data.vertices, colorAttr, 'string'));
        this.createLegend(colorMap, s);
      } else {
        console.error('The type of ' +
          side.attrs.color + ' (' + side.attrs.color.typeName +
          ') is not supported for vertex color visualization!');
      }
    }

    for (var i = 0; i < data.vertices.length; ++i) {
      var vertex = data.vertices[i];

      var label;
      if (side.attrs.label) { label = vertex.attrs[side.attrs.label.id].string; }

      var size = 0.5;
      if (sizeAttr) { size = vertex.attrs[sizeAttr].double / sizeMax; }

      var color = UNCOLORED;
      if (colorAttr) {
        // in case of doubles the keys are strings converted from the DynamicValue's double field
        // we can't just use the string field of the DynamicValue as 1.0 would turn to '1'
        color = (side.attrs.color.typeName === 'Double') ?
          colorMap[vertex.attrs[colorAttr].double] : colorMap[vertex.attrs[colorAttr].string];
      }

      var icon;
      if (side.attrs.icon) { icon = vertex.attrs[side.attrs.icon.id].string; }

      var radius = 0.1 * Math.sqrt(size);
      var v = new Vertex(vertex,
                         Math.random() * 400 - 200,
                         Math.random() * 400 - 200,
                         radius,
                         label,
                         vertex.id,
                         color,
                         icon);
      offsetter.rule(v);
      v.id = vertex.id.toString();
      svg.addClass(v.dom, 'sampled');
      if (side.centers.indexOf(v.id) > -1) {
        svg.addClass(v.dom, 'center');
      }
      vertices.push(v);

      this.sampledVertexMouseBindings(vertices, v);
      this.vertexGroup.append(v.dom);
    }

    return vertices;
  };

  GraphView.prototype.createLegend = function (colorMap, side) {
    var margin = 50;
    var x = side === 'left' ? margin : this.svg.width() - margin;
    var anchor = side === 'left' ? 'start' : 'end';
    var i = 0;
    for (var attr in colorMap) {
      var l = svg.create('text', { 'class': 'legend', x: x, y: i * 22 + margin })
        .text(attr || 'undefined');
      l.attr('fill', colorMap[attr] || UNCOLORED);
      l.attr('text-anchor', anchor);
      this.legend.append(l);
      i++;
    }
  };

  function translateTouchToMouseEvent(ev) {
    if (ev.type === 'touchmove') {
      // Translate into mouse event.
      ev.pageX = ev.originalEvent.changedTouches[0].pageX;
      ev.pageY = ev.originalEvent.changedTouches[0].pageY;
      ev.preventDefault();
    }
  }

  GraphView.prototype.sampledVertexMouseBindings = function(vertices, vertex) {
    var scope = this.scope;
    var svgElement = this.svg;
    vertex.dom.on('mousedown touchstart', function(evStart) {
      evStart.stopPropagation();
      vertex.held = true;
      vertex.dragged = false;
      angular.element(window).on('mouseup touchend', function() {
        angular.element(window).off('mousemove mouseup touchmove touchend');
        if (!vertex.held) {
          return;  // Duplicate event.
        }
        vertex.held = false;
        if (vertex.dragged) {  // It was a drag.
          vertex.dragged = false;
          vertices.animate();
        } else {  // It was a click.
          scope.$apply(function() {
            var actions = [];
            var side = vertices.side;
            var id = vertex.id.toString();
            if (!side.hasCenter(id)) {
              actions.push({
                title: 'Add to centers',
                callback: function() {
                  side.addCenter(id);
                },
              });
            }
            if (side.hasCenter(id)) {
              actions.push({
                title: 'Remove from centers',
                callback: function() {
                  side.removeCenter(id);
                },
              });
            }
            if (!side.hasCenter(id) || (side.centers.length !== 1)) {
              actions.push({
                title: 'Set as only center',
                callback: function() {
                  side.setCenter(id);
                },
              });
            }
            if (side.hasParent()) {
              if (side.isParentFilteredToSegment(id)) {
                actions.push({
                  title: 'Stop filtering base project to this segment',
                  callback: function() {
                    side.deleteParentsSegmentFilter();
                  },
                });
              } else {
                actions.push({
                  title: 'Filter base project to this segment',
                  callback: function() {
                    side.filterParentToSegment(id);
                  },
                });
              }
            }
            if (vertex.frozen) {
              actions.push({
                title: 'Unfreeze',
                callback: function() {
                  vertex.frozen -= 1;
                },
              });
            } else {
              actions.push({
                title: 'Freeze',
                callback: function() {
                  vertex.frozen += 1;
                },
              });
            }

            vertex.activateMenu({
              header: 'Vertex ' + id,
              type: 'vertex',
              id: id,
              actions: actions,
            });
          });
        }
      });
      angular.element(window).on('mousemove touchmove', function(ev) {
        translateTouchToMouseEvent(ev);
        var offsetter = vertex.offsetter;
        var x = (ev.pageX - svgElement.offset().left - offsetter.xOff) / offsetter.zoom;
        var y = (ev.pageY - svgElement.offset().top - offsetter.yOff) / offsetter.zoom;
        vertex.moveTo(x, y);
        vertex.forceOX = x;
        vertex.forceOY = y;
        vertex.dragged = true;
        vertices.animate();
      });
    });
  };

  GraphView.prototype.sideMouseBindings = function(offsetter, xMin, xMax) {
    var svgElement = this.svg;
    this.svgMouseDownListeners.push(function(evStart) {
      translateTouchToMouseEvent(evStart);
      var svgX = evStart.pageX - svgElement.offset().left;
      if ((svgX < xMin) || (svgX >= xMax)) {
        return;
      }
      var evXToXOff = offsetter.xOff - evStart.pageX;
      var evYToYOff = offsetter.yOff - evStart.pageY;
      angular.element(window).on('mousemove touchmove', function(evMoved) {
        translateTouchToMouseEvent(evMoved);
        offsetter.panTo(evMoved.pageX + evXToXOff, evMoved.pageY + evYToYOff);
      });
      angular.element(window).on('mouseup touchend', function() {
        angular.element(window).off('mousemove mouseup touchmove touchend');
      });
    });
    this.svgMouseWheelListeners.push(function(e) {
      var mx = e.originalEvent.pageX;
      var my = e.originalEvent.pageY;
      var svgX = mx - svgElement.offset().left;
      if ((svgX < xMin) || (svgX >= xMax)) {
        return;
      }
      e.preventDefault();
      var delta = -0.001 * e.originalEvent.deltaY;
      // Graph-space point under the mouse should remain unchanged.
      // mxOff * zoom + xOff = mx
      var mxOff = (mx - offsetter.xOff) / offsetter.zoom;
      var myOff = (my - offsetter.yOff) / offsetter.zoom;
      offsetter.zoom *= Math.exp(delta);
      offsetter.xOff = mx - mxOff * offsetter.zoom;
      offsetter.yOff = my - myOff * offsetter.zoom;
      // Thickness (vertex radius and edge width) changes by a square-root function.
      offsetter.thickness *= Math.exp(0.5 * delta);
      offsetter.reDraw();
    });
  };

  GraphView.prototype.initSampled = function(vertices) {
    this.initLayout(vertices);
    this.initZoom(vertices);
    this.initSlider(vertices);
  };

  GraphView.prototype.initZoom = function(vertices) {
    // Initial zoom to fit the layout on the SVG.
    var xb = common.minmax(vertices.map(function(v) { return v.x; }));
    var yb = common.minmax(vertices.map(function(v) { return v.y; }));
    var xFit = 0.25 * this.svg.width() / Math.max(Math.abs(xb.min), Math.abs(xb.max));
    var yFit = 0.5 * this.svg.height() / Math.max(Math.abs(yb.min), Math.abs(yb.max));
    // Avoid infinite zoom for 1-vertex graphs.
    if (!isFinite(xFit) && !isFinite(yFit)) { return; }
    var newZoom = graphToSVGRatio * Math.min(xFit, yFit);

    // Apply the calculated zoom if it is a new offsetter, or if the inherited zoom is way off.
    var ratio = newZoom / vertices.offsetter.zoom;
    if (!vertices.offsetter.inherited || ratio < 0.1 || ratio > 10) {
      vertices.offsetter.zoom = newZoom;
      // "Thickness" is scaled to the SVG size. We leave it unchanged.
      vertices.offsetter.reDraw();
    }
  };

  GraphView.prototype.initSlider = function(vertices) {
    this.unregistration.push(this.scope.$watch(sliderPos, onSlider));
    function sliderPos() {
      return vertices.side.sliderPos;
    }
    function onSlider() {
      var sliderAttr = vertices.side.attrs.slider;
      if (!sliderAttr) { return; }
      var sb = common.minmax(
          vertices.map(function(v) { return v.data.attrs[sliderAttr.id].double; }));
      var pos = sliderPos();
      for (var i = 0; i < vertices.length; ++i) {
        var v = vertices[i];
        var x = v.data.attrs[sliderAttr.id].double;
        var norm = Math.floor(100 * common.normalize(x, sb) + 50);  // Normalize to 0 - 100.
        if (norm < pos) {
          v.color = 'hsl(100, 50%, 42%)';
        } else if (norm > pos) {
          v.color = 'hsl(0, 50%, 42%)';
        } else {
          v.color = 'hsl(50, 50%, 42%)';
        }
        v.icon.attr({ style: 'fill: ' + v.color });
      }
    }
  };

  GraphView.prototype.initLayout = function(vertices) {
    for (var i = 0; i < vertices.length; ++i) {
      var v = vertices[i];
      v.forceMass = 1;
      v.forceOX = v.x;
      v.forceOY = v.y;
    }
    if (vertices.edges !== undefined) {
      for (i = 0; i < vertices.edges.length; ++i) {
        var e = vertices.edges[i];
        e.src.forceMass += 1;
        e.dst.forceMass += 1;
      }
    }
    var scale = this.svg.height();
    var engine = new FORCE_LAYOUT.Engine({
      attraction: 0.01,
      repulsion: scale,
      gravity: 0.05,
      drag: 0.2,
      labelAttraction: parseFloat(vertices.side.animate.labelAttraction),
    });
    // Initial layout.
    var t1 = Date.now();
    while (engine.calculate(vertices) && Date.now() - t1 <= 2000) {}
    engine.apply(vertices);
    var animating = false;
    // Call vertices.animate() later to trigger interactive layout.
    vertices.animate = function() {
      if (!animating) {
        animating = true;
        window.requestAnimationFrame(vertices.step);
      }
    };
    vertices.step = function() {
      engine.opts.labelAttraction = parseFloat(vertices.side.animate.labelAttraction);
      if (animating && vertices.side.animate.enabled && engine.step(vertices)) {
        window.requestAnimationFrame(vertices.step);
      } else {
        animating = false;
      }
    };
    vertices.animate();
    // Kick off animation when the user manually turns it on.
    var unwatch = util.deepWatch(this.scope,
        function() { return vertices.side.animate; },
        function() { vertices.animate(); });
    this.unregistration.push(function() {
      unwatch();
      animating = false;
    });
  };

  GraphView.prototype.addBucketedVertices = function(data, offsetter, viewData) {
    var vertices = [];
    vertices.mode = 'bucketed';
    vertices.offsetter = offsetter;
    var xLabels = [], yLabels = [];
    var i, x, y, l, side;
    var labelSpace = 0.05;
    y = 0.5 + labelSpace;
    
    var xb = common.minmax(data.vertices.map(function(n) { return n.x; }));
    var yb = common.minmax(data.vertices.map(function(n) { return n.y; }));
    
    var xNumBuckets = xb.span + 1;
    var yNumBuckets = yb.span + 1;

    y = 0.5 + labelSpace;
    if (viewData.xAttribute) {
      // Label the X axis with the attribute name.
      l = new Label(
          0, y - labelSpace, viewData.xAttribute.title,
          { classes: 'axis-label' });
      offsetter.rule(l);
      this.vertexGroup.append(l.dom);
    }
    for (i = 0; i < data.xLabels.length; ++i) {
      if (data.xLabelType === 'between') {
        x = common.normalize(i, xNumBuckets);
      } else {
        x = common.normalize(i + 0.5, xNumBuckets);
      }
      l = new Label(x, y, data.xLabels[i]);
      offsetter.rule(l);
      xLabels.push(l);
      this.vertexGroup.append(l.dom);
    }
    // Labels on the left on the left and on the right on the right.
    if (offsetter.xOff < this.svg.width() / 2) {
      x = -0.5 - labelSpace;
      side = 'left';
    } else {
      x = 0.5 + labelSpace;
      side = 'right';
    }
    if (viewData.yAttribute) {
      // Label the Y axis with the attribute name.
      var mul = side === 'left' ? 1 : -1;
      l = new Label(
          x + mul * labelSpace, 0, viewData.yAttribute.title,
          { vertical: true, classes: 'axis-label' });
      offsetter.rule(l);
      this.vertexGroup.append(l.dom);
    }
    for (i = 0; i < data.yLabels.length; ++i) {
      if (data.yLabelType === 'between') {
        y = -common.normalize(i, yNumBuckets);
      } else {
        y = -common.normalize(i + 0.5, yNumBuckets);
      }
      l = new Label(x, y, data.yLabels[i], { classes: side });
      offsetter.rule(l);
      yLabels.push(l);
      this.vertexGroup.append(l.dom);
    }
     
    var sizes = data.vertices.map(function(n) { return n.size; });
    var vertexScale = 1 / common.minmax(sizes).max;
    for (i = 0; i < data.vertices.length; ++i) {
      var vertex = data.vertices[i];
      var radius = 0.1 * Math.sqrt(vertexScale * vertex.size);
      var v = new Vertex(vertex,
                         common.normalize(vertex.x + 0.5, xNumBuckets),
                         -common.normalize(vertex.y + 0.5, yNumBuckets),
                         radius,
                         vertex.size);
      offsetter.rule(v);
      vertices.push(v);
      if (vertex.size === 0) {
        continue;
      }
      this.vertexGroup.append(v.dom);
      if (xLabels.length !== 0) {
        v.addHoverListener(xLabels[vertex.x]);
        if (data.xLabelType === 'between') { v.addHoverListener(xLabels[vertex.x + 1]); }
      }
      if (yLabels.length !== 0) {
        v.addHoverListener(yLabels[vertex.y]);
        if (data.yLabelType === 'between') { v.addHoverListener(yLabels[vertex.y + 1]); }
      }
    }
    return vertices;
  };

  GraphView.prototype.addEdges = function(edges, srcs, dsts) {
    var edgeObjects = [];
    var bounds = common.minmax(edges.map(function(n) { return n.size; }));
    var normalWidth = 0.02;
    var info = bounds.span / bounds.max;  // Information content of edge widths. (0 to 1)
    // Go up to 3x thicker lines if they are meaningful.
    var edgeScale = normalWidth * (1 + info * 2) / bounds.max;
    for (var i = 0; i < edges.length; ++i) {
      var edge = edges[i];
      if (edge.size === 0) {
        continue;
      }
      var a = srcs[edge.a];
      var b = dsts[edge.b];
      var e = new Edge(a, b, edgeScale * edge.size);
      edgeObjects.push(e);
      this.edgeGroup.append(e.dom);
    }
    return edgeObjects;
  };

  function Label(x, y, text, opts) {
    opts = opts || {};
    var classes = 'bucket ' + (opts.classes || '');
    this.x = x;
    this.y = y;
    this.vertical = opts.vertical;
    this.dom = svg.create('text', { 'class': classes }).text(text);
    if (this.vertical) {
      this.dom.attr({ transform: svgRotate(-90) });
    }
  }
  Label.prototype.on = function() { svg.addClass(this.dom, 'highlight'); };
  Label.prototype.off = function() { svg.removeClass(this.dom, 'highlight'); };
  Label.prototype.reDraw = function() {
    if (this.vertical) {
      this.dom.attr({x: -this.screenY(), y: this.screenX()});
    } else {
      this.dom.attr({x: this.screenX(), y: this.screenY()});
    }
  };

  function Vertex(data, x, y, r, text, subscript, color, icon) {
    this.data = data;
    this.x = x;
    this.y = y;
    this.r = r;
    this.color = color || UNCOLORED;
    this.highlight = 'white';
    this.frozen = 0;  // Number of reasons why this vertex should not be animated.
    this.icon = getIcon(icon);
    this.icon.attr({ style: 'fill: ' + this.color, 'class': 'icon' });
    this.minTouchRadius = 10;
    if (r < this.minTouchRadius) {
      this.touch = svg.create('circle', { 'class': 'touch' });
    } else {
      this.touch = this.icon;
    }
    this.text = text;
    this.label = svg.create('text').text(text || '');
    this.subscript = svg.create('text', { 'class': 'subscript' }).text(subscript);
    this.labelBackground = svg.create(
        'rect', { 'class': 'label-background', width: 0, height: 0, rx: 2, ry: 2 });
    this.dom = svg.group(
        [this.icon, this.touch, this.labelBackground, this.label, this.subscript],
        {'class': 'vertex' });
    this.moveListeners = [];
    this.hoverListeners = [];
    var that = this;
    this.touch.mouseenter(function() {
      svg.addClass(that.dom, 'highlight');
      that.icon.attr({style: 'fill: ' + that.highlight});
      for (var i = 0; i < that.hoverListeners.length; ++i) {
        that.hoverListeners[i].on(that);
      }
      // Size labelBackground here, because we may not know the label size earlier.
      that.labelBackground.attr({ width: that.label.width() + 4, height: that.label.height() });
      that.reDraw();
    });
    this.touch.mouseleave(function() {
      svg.removeClass(that.dom, 'highlight');
      that.icon.attr({style: 'fill: ' + that.color});
      for (var i = 0; i < that.hoverListeners.length; ++i) {
        that.hoverListeners[i].off(that);
      }
    });
  }
  // Hover listeners must have an `on()` and an `off()` method.
  Vertex.prototype.addHoverListener = function(hl) {
    this.hoverListeners.push(hl);
  };
  Vertex.prototype.addMoveListener = function(ml) {
    this.moveListeners.push(ml);
  };
  Vertex.prototype.moveTo = function(x, y) {
    this.x = x;
    this.y = y;
    this.reDraw();
  };
  function svgTranslate(x, y) { return ' translate(' + x + ' ' + y + ')'; }
  function svgScale(s) { return ' scale(' + s + ')'; }
  function svgRotate(deg) { return ' rotate(' + deg + ')'; }
  Vertex.prototype.reDraw = function() {
    var sx = this.screenX(), sy = this.screenY();
    var r = this.offsetter.thickness * this.r;
    this.icon.attr({ transform:
      svgTranslate(sx, sy) +
      svgScale(r * this.icon.scale) +
      svgTranslate(-this.icon.center.x, -this.icon.center.y) });
    this.touch.attr({ cx: sx, cy: sy, r: r });
    this.label.attr({ x: sx, y: sy });
    var backgroundWidth = this.labelBackground.attr('width');
    var backgroundHeight = this.labelBackground.attr('height');
    this.labelBackground.attr({ x: sx - backgroundWidth / 2, y: sy - backgroundHeight / 2 });
    this.subscript.attr({ x: sx, y: sy - 12 });
    for (var i = 0; i < this.moveListeners.length; ++i) {
      this.moveListeners[i](this);
    }
  };

  function Edge(src, dst, w) {
    this.src = src;
    this.dst = dst;
    this.w = w;
    this.first = svg.create('path', { 'class': 'first' });
    this.second = svg.create('path', { 'class': 'second' });
    this.dom = svg.group([this.first, this.second], {'class': 'edge'});
    var that = this;
    src.addMoveListener(function() { that.reposition(); });
    dst.addMoveListener(function() { that.reposition(); });
    this.reposition();
    src.addHoverListener({
      on: function() { svg.addClass(that.dom, 'highlight-out'); that.toFront(); },
      off: function() { svg.removeClass(that.dom, 'highlight-out'); }
    });
    if (src !== dst) {
      dst.addHoverListener({
        on: function() { svg.addClass(that.dom, 'highlight-in'); that.toFront(); },
        off: function() { svg.removeClass(that.dom, 'highlight-in'); }
      });
    }
  }
  Edge.prototype.toFront = function() {
    this.dom.parent().append(this.dom);
  };
  Edge.prototype.reposition = function() {
    var src = this.src, dst = this.dst;
    var avgZoom = 0.5 * (src.offsetter.thickness + dst.offsetter.thickness);
    this.first.attr({
      d: svg.arrow1(
        src.screenX(), src.screenY(), dst.screenX(), dst.screenY(), avgZoom),
      'stroke-width': avgZoom * this.w,
    });
    this.second.attr({
      d: svg.arrow2(
        src.screenX(), src.screenY(), dst.screenX(), dst.screenY(), avgZoom),
      'stroke-width': avgZoom * this.w,
    });
  };

  return directive;
});
