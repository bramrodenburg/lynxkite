'use strict';


module.exports = function(fw) {
  var lib = require('../test-lib.js');
  var path = require('path');
  var fs = require('fs');

  fw.transitionTest(
    'empty test-example workspace',
    'plot data imported as table',
    function() {
      lib.workspace.addBox({
        id: 'ib0',
        name: 'Import CSV',
        x: 100, y: 100 });
      var boxEditor = lib.workspace.openBoxEditor('ib0');
      var importPath = path.resolve(__dirname, 'data/plot_data.csv');
      boxEditor.populateOperation({
        'filename': importPath,
        'columns': 'product,cnt'
      });
      lib.loadImportedTable();
      boxEditor.close();
    },
    function() {
    }
  );

  fw.transitionTest(
    'plot data imported as table',
    'plot viewer opened',
    function() {
      lib.workspace.addBox({
        id: 'plot1',
        name: 'Custom plot',
        x: 200, y: 200 });
      lib.workspace.connectBoxes('ib0', 'table', 'plot1', 'table');
      var plotCode = fs.readFileSync(__dirname + '/data/plot_code.scala', 'utf8');
      var boxEditor = lib.workspace.openBoxEditor('plot1');
      boxEditor.populateOperation({
        'plot_code': plotCode,
      });
      boxEditor.close();
      var plotState = lib.workspace.openStateView('plot1', 'plot');
      var plot = plotState.plot;
      plot.expectBarHeightsToBe(['97', '191', '149', '316']);
    },
    function() {
    }
  );

};

