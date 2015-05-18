// Utility functions for SVG building.
'use strict';

function arcParams(ax, ay, bx, by, zoom) {
  if (ax === bx && ay === by) {
    return {r: 0.1 * zoom, x: ax + 0.2 * zoom, y: ay};
  } else {
    var dx = bx - ax, dy = by - ay;
    var d = Math.sqrt(dx * dx + dy * dy);
    // Use larger radius (less curvature) for long distance edges.
    var r = d * (d + 1000) / 1000;
    var h = r - Math.sqrt(r * r - d * d / 4);
    return {
      r: r,
      x: ax + 0.5 * dx - h * dy / d,
      y: ay + 0.5 * dy + h * dx / d,
    };
  }
}

/* exported  SVG_UTIL */
var SVG_UTIL = {
  // JQuery addClass/removeClass does not work on SVG elements. (They are in
  // another namespace, but the "class" attribute is in the default namespace.)
  classesOf: function(e) {
    var l = e[0].getAttributeNS(null, 'class').split(' ');
    l.plus = function(cls) {
      if (l.indexOf(cls) === -1) {
        return l.concat(cls);
      } else {
        return l;
      }
    };
    l.minus = function(cls) {
      var i = l.indexOf(cls);
      if (i === -1) {
        return l;
      } else {
        return l.slice(0, i).concat(l.slice(i + 1));
      }
    };
    return l;
  },

  addClass: function(e, cls) {
    e[0].setAttributeNS(null, 'class', SVG_UTIL.classesOf(e).plus(cls).join(' '));
  },

  removeClass: function(e, cls) {
    e[0].setAttributeNS(null, 'class', SVG_UTIL.classesOf(e).minus(cls).join(' '));
  },

  draw: function(objects) {
    for (var i =0;i<objects.length;i++) {
      if (!isNaN(objects[i]) && objects[i] % 1 !== 0) {
        objects[i] = objects[i].toFixed(3);
      }
    }
    return ' ' + objects.join(' ') + ' ';
  },

  arcParams: function(ax, ay, bx, by, zoom) { return arcParams(ax, ay, bx, by, zoom); },

  arc: function(r, x, y, dir) { return SVG_UTIL.draw(['A', r, r, 0, 0, dir, x, y]); },

  arrow1: function(ax, ay, bx, by, zoom) {
    var a = arcParams(ax, ay, bx, by, zoom);
    return SVG_UTIL.draw(['M', ax, ay]) + SVG_UTIL.arc(a.r, a.x, a.y, 0);
  },

  arrow2: function(ax, ay, bx, by, zoom) {
    var a = arcParams(ax, ay, bx, by, zoom);
    return SVG_UTIL.draw(['M', bx, by]) + SVG_UTIL.arc(a.r, a.x, a.y, 1);
  },

  group: function(l, attrs) {
    var g = SVG_UTIL.create('g', attrs);
    g.append(l);
    return g;
  },

  marker: function(id) {
    var m = SVG_UTIL.create('marker');
    m.attr({'id': id, 'orient': 'auto'});
    m[0].setAttributeNS(null, 'viewBox', '-3 -5 7 10');
    var p = SVG_UTIL.create('path');
    p.attr({'d': 'M -3 -5 l 10 5 l -10 5 z'});
    m.append(p);
    return m;
  },

  create: function(tag, attrs) {
    var e = angular.element(document.createElementNS('http://www.w3.org/2000/svg', tag));
    if (attrs !== undefined) {
      for (var k in attrs) {
        e[0].setAttributeNS(null, k, attrs[k]);
      }
    }
    return e;
  },
};
