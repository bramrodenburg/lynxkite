'use strict';

module.exports = function() {};

var lib = require('../test-lib.js');

module.exports = function(fw) {
  fw.transitionTest(
    'empty test-example workspace',
    'example graph workspace',
    function() {
      lib.workspace.addBoxFromSelector('Create example graph');
    },
    function() {
      lib.workspace.expectNumBoxes(2); // With anchor box
    });
};
