'use strict';

var lib = require('../test-lib.js');
var left = lib.left;
var right = lib.right;

module.exports = function(fw) {
  fw.statePreservingTest(
    'test-example project with example graph',
    'SQL default query works',
    function() {
      left.runSql();

      left.expectSqlResult(
        ['age', 'gender', 'id', 'income', 'location', 'name'],
        [
          ['20.3', 'Male', '0', '1000.0', '[40.71448,-74.00598]', 'Adam'],
          ['18.2', 'Female', '1', 'null', '[47.5269674,19.0323968]', 'Eve'],
          ['50.3', 'Male', '2', '2000.0', '[1.352083,103.819836]', 'Bob'],
          ['2.0', 'Male', '3', 'null', '[-33.8674869,151.2069902]', 'Isolated Joe'],
        ]);
    });

  fw.transitionTest(
    'test-example project with example graph',
    'SQL creating segmentation works',
    function() {
      left.runSql('select age, gender, name from vertices');
      left.startSqlSaving();

      // Choose in-project table format, and save.
      left.side.element(by.css('#exportFormat option[value="segmentation"]')).click();
      left.side.element(by.css('#exportKiteTable')).sendKeys('exported_table');
      left.executeSqlSaving();
      lib.left.openSegmentation('exported_table');
      expect(lib.right.segmentCount()).toEqual(4);
      expect(lib.right.vertexAttribute('age').isPresent()).toBe(true);
      expect(lib.right.vertexAttribute('gender').isPresent()).toBe(true);
      expect(lib.right.vertexAttribute('name').isPresent()).toBe(true);
    }, function() {});

  fw.statePreservingTest(
    'test-example project with example graph',
    'SQL works for edge attributes',
    function() {
      left.runSql('select edge_comment, src_name from edges order by edge_comment');

      left.expectSqlResult(
        ['edge_comment', 'src_name'],
        [
          [ 'Adam loves Eve', 'Adam' ],
          [ 'Bob envies Adam', 'Bob' ],
          [ 'Bob loves Eve', 'Bob' ],
          [ 'Eve loves Adam', 'Eve' ],
        ]);
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    '"order by" works right',
    function() {
      left.runSql('select id, name from vertices order by name');

      left.expectSqlResult(
        ['id', 'name'],
        [
          [ '0', 'Adam' ],
          [ '2', 'Bob' ],
          [ '1', 'Eve' ],
          [ '3', 'Isolated Joe' ],
        ]);
    });

  fw.transitionTest(
    'empty test-example project',
    'SQL runs nice on belongs to reached from project and segmentation',
    function() {
      left.runOperation('New vertex set', { size: '100' });
      left.runOperation('Add random vertex attribute', { seed: '1' });
      left.runOperation('Copy graph into a segmentation');
      left.openSegmentation('self_as_segmentation');
      left.runSql(
        'select sum(base_random / segment_random) as sum from `self_as_segmentation|belongs_to`');
      right.runSql('select sum(base_random - segment_random) as error from belongs_to');
    },
    function() {
      left.expectSqlResult(['sum'], [['100.0']]);
      right.expectSqlResult(['error'], [['0.0']]);
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'Save SQL result as CSV works',
    function() {
      left.setSql('select name, age, income from vertices order by name');

      left.startSqlSaving();

      // Choose csv format.
      left.side.$('#exportFormat option[value="csv"]').click();

      // And go.
      lib.startDownloadWatch();
      left.executeSqlSaving();
      var downloadedFileName = lib.waitForNewDownload(/\.csv$/);
      lib.expectFileContents(
        downloadedFileName,
        'name,age,income\n' +
        'Adam,20.3,1000.0\n' +
        'Bob,50.3,2000.0\n' +
        'Eve,18.2,\n' +
        'Isolated Joe,2.0,\n');
    });

  fw.transitionTest(
    'empty test-example project',
    'table export and reimport',
    function() {
      left.runOperation('New vertex set', { size: '100' });
      left.runOperation('Add random vertex attribute', { name: 'random1', seed: '1' });
      left.runOperation('Add random vertex attribute', { name: 'random2', seed: '2' });
      left.runOperation('Add rank attribute', { keyattr: 'random1', rankattr: 'rank1' });
      left.runOperation('Add rank attribute', { keyattr: 'random2', rankattr: 'rank2' });

      left.setSql(
        'select cast(rank1 as string), cast(rank2 as string) from vertices');
      left.startSqlSaving();
      left.side.$('#exportFormat option[value="table"]').click();
      left.side.$('#exportKiteTable').sendKeys('Random Edges');
      left.executeSqlSaving();

      left.runOperation('Vertex attribute to double', { attr: 'ordinal' });
      left.runOperation('Vertex attribute to string', { attr: 'ordinal' });
      left.runOperation(
        'Import edges for existing vertices',
        {
          table: 'Random Edges',
          attr: 'ordinal',
          src: 'rank1',
          dst: 'rank2',
        });

      left.runSql('select sum(rank1) as r1sum, sum(rank2) as r2sum from edge_attributes');
      left.expectSqlResult(['r1sum', 'r2sum'], [['4950.0', '4950.0']]);

      left.runSql(
        'select min(edge_rank1 = src_ordinal) as srcgood, min(edge_rank2 = dst_ordinal) as dstgood from edges');
      left.expectSqlResult(['srcgood', 'dstgood'], [['true', 'true']]);
    },
    function() {
    });

  fw.transitionTest(
    'test-example project with example graph',
    'parquet export and reimport right from the operation',
    function() {
      left.setSql(
        'select name, age, gender, income from vertices');
      left.startSqlSaving();
      left.side.$('#exportFormat option[value="parquet"]').click();
      var fileName = 'UPLOAD$/example.' + process.pid + '.parquet';
      left.side.$('#export-parquet-path').sendKeys(fileName);
      left.executeSqlSaving();

      left.runOperation('Discard vertices');
      left.openOperation('Import vertices');
      var tableKind = left.operationParameter(left.toolbox, 'table');
      tableKind.element(by.id('import-new-table-button')).click();
      tableKind.$('#table-name input').sendKeys('example reloaded as parquet');
      tableKind.element(by.cssContainingText('#datatype option', 'Parquet files')).click();
      tableKind.$('#parquet-filename input[ng-model="filename"]').sendKeys(fileName);
      tableKind.element(by.id('import-parquet-button')).click();
      left.submitOperation(left.toolbox);
    },
    function() {
      expect(lib.left.vertexCount()).toEqual(4);
      // id, name, age, gender, income
      expect(lib.left.attributeCount()).toEqual(5);
    });

  fw.transitionTest(
    'empty test-example project',
    'test-example project with 100 vertices',
    function() {
      left.runOperation('New vertex set', { size: '100'});
      var maxRows = left.side.element(by.css('#max-rows'));

      maxRows.clear().sendKeys('1000');
      left.runSql('select * from vertices');
      expect(left.side.all(by.css('#sql-result table tbody tr')).count()).toEqual(100);
      maxRows.clear().sendKeys('100');
      left.runSql('select * from vertices');
      expect(left.side.all(by.css('#sql-result table tbody tr')).count()).toEqual(100);
      maxRows.clear().sendKeys('17');
      left.runSql('select * from vertices');
      expect(left.side.all(by.css('#sql-result table tbody tr')).count()).toEqual(17);
    },
    function() {
    },true
    );

};
