'use strict';

/* global element, by, protractor */

var testLib; // Forward declarations.
var History; // Forward declarations.
var request = require('request');
var K = protractor.Key;  // Short alias.

// Mirrors the "id" filter.
function toID(x) {
  return x.toLowerCase().replace(/ /g, '-');
}

function Side(direction) {
  this.direction = direction;
  this.side = element(by.id('side-' + direction));
  this.toolbox = element(by.id('operation-toolbox-' + direction));
  this.history = new History(this);
}

Side.prototype = {
  expectCurrentProjectIs: function(name) {
    expect(this.side.evaluate('side.state.projectName'), name);
  },

  // Only for opening the second project next to an already open project.
  openSecondProject: function(project) {
    this.side.element(by.id('show-selector-button')).click();
    this.side.element(by.id('project-' + toID(project))).click();
  },

  close: function() {
    this.side.element(by.id('close-project')).click();
  },

  evaluate: function(expr) {
    return this.side.evaluate(expr);
  },

  applyFilters: function() {
    return this.side.element(by.id('apply-filters-button')).click();
  },

  getCategorySelector: function(categoryTitle) {
    return this.toolbox.element(by.css('div.category[tooltip="' + categoryTitle + '"]'));
  },

  getHistogram: function(attributeName) {
    return this.side.element(by.css('histogram[attr-name="' + attributeName + '"]'));
  },

  getHistogramButton: function(attributeName) {
    return this.side.element(by.css('#histogram-button[attr-name="' + attributeName + '"]'));
  },

  getHistogramPreciseCheckbox: function(attributeName) {
    return this.side.element(by.css('#precise-histogram-calculation[attr-name="' + attributeName + '"]'));
  },

  getHistogramTotalElement: function(attributeName) {
    return this.getHistogram(attributeName).element(by.css('.histogram-total'));
  },

  getHistogramValues: function(attributeName, precise) {
    precise = precise || false;
    var histogramButton = this.getHistogramButton(attributeName);
    var preciseButton = this.getHistogramPreciseCheckbox(attributeName);
    var total = this.getHistogramTotalElement(attributeName);
    var histo = this.getHistogram(attributeName);
    expect(histo.isDisplayed()).toBe(false);
    expect(total.isDisplayed()).toBe(false);
    histogramButton.click();
    if (precise) {
      preciseButton.click();
    }
    expect(histo.isDisplayed()).toBe(true);
    expect(total.isDisplayed()).toBe(false);
    function allFrom(td) {
      var toolTip = td.getAttribute('drop-tooltip');
      var style = td.element(by.css('div.bar')).getAttribute('style');
      return protractor.promise.all([toolTip, style]).then(function(results) {
        var toolTipMatch = results[0].match(/^(.*): (\d+)$/);
        var styleMatch = results[1].match(/^height: (\d+)%;$/);
        return {
          title: toolTipMatch[1],
          size: parseInt(styleMatch[1]),
          value: parseInt(toolTipMatch[2]),
        };
      });
    }
    var tds = histo.all(by.css('td'));
    var res = tds.then(function(tds) {
      var res = [];
      for (var i = 0; i < tds.length; i++) {
        res.push(allFrom(tds[i]));
      }
      return protractor.promise.all(res);
    });

    browser.actions().mouseMove(tds.first()).perform();
    expect(total.isDisplayed()).toBe(true);
    protractor.promise.all([total.getText(), res]).then(function(results) {
      var totalValue = results[0].match(/total: ([0-9,]+)/)[1];
      var values = results[1];
      var total = parseInt(totalValue.replace(/,/g, ''));
      var sum = 0;
      for (var j = 0; j < values.length; j++) {
        sum += values[j].value;
      }
      expect(total).toEqual(sum);
    });

    if (precise) {
      preciseButton.click();
    }
    histogramButton.click();
    expect(histo.isDisplayed()).toBe(false);
    expect(total.isDisplayed()).toBe(false);
    return res;
  },

  getProjectHistory: function() {
    return this.side.element(by.css('div.project.history'));
  },

  getValue: function(id) {
    var asStr = this.side.element(by.css('value#' + id + ' span.value')).getText();
    return asStr.then(function(asS) { return parseInt(asS); });
  },

  getWorkflowCodeEditor: function() {
    return this.side.element(by.id('workflow-code-editor'));
  },

  getWorkflowDescriptionEditor: function() {
    return this.side.element(by.id('workflow-description'));
  },

  getWorkflowNameEditor: function() {
    return this.side.element(by.id('workflow-name'));
  },

  clickWorkflowEditButton: function() {
    return this.toolbox.element(by.id('edit-operation-button')).click();
  },

  getWorkflowSaveButton: function() {
    return this.side.element(by.id('save-workflow-button'));
  },

  edgeCount: function() {
    return this.getValue('edge-count');
  },

  vertexCount: function() {
    return this.getValue('vertex-count');
  },

  segmentCount: function() {
    return this.getValue('segment-count');
  },

  openOperation: function(name) {
    this.toolbox.element(by.id('operation-search')).click();
    this.toolbox.element(by.id('filter')).sendKeys(name, K.ENTER);
  },

  closeOperation: function() {
    this.toolbox.element(by.css('div.category.active')).click();
  },

  openWorkflowSavingDialog: function() {
    this.side.element(by.id('save-as-workflow-button')).click();
  },

  closeWorkflowSavingDialog: function() {
    this.side.element(by.id('close-workflow-button')).click();
  },

  openSegmentation: function(segmentationName) {
    this.side.element(by.id('segmentation-' + segmentationName)).click();
  },

  redoButton: function() {
    return this.side.element(by.id('redo-button'));
  },

  operationParameter: function(opElement, param) {
    return opElement.element(by.css(
      'operation-parameters #' + param + ' .operation-attribute-entry'));
  },

  populateOperation: function(parentElement, params) {
    params = params || {};
    for (var key in params) {
      testLib.setParameter(this.operationParameter(parentElement, key), params[key]);
    }
  },

  populateOperationInput: function(parameterId, param) {
    this.toolbox.element(by.id(parameterId)).sendKeys(testLib.selectAllKey + param);
  },

  submitOperation: function(parentElement) {
    var button = parentElement.element(by.css('.ok-button'));
    // Wait for uploads or whatever.
    testLib.wait(protractor.until.elementTextMatches(button, /OK/));
    button.click();
  },

  runOperation: function(name, params) {
    this.openOperation(name);
    this.populateOperation(this.toolbox, params);
    this.submitOperation(this.toolbox);
  },

  expectOperationScalar: function(name, text) {
    var cssSelector = 'value[ref="scalars[\'' + name + '\']"';
    var valueElement = this.toolbox.element(by.css(cssSelector));
    expect(valueElement.getText()).toBe(text);
  },

  setAttributeFilter: function(attributeName, filterValue) {
    var filterBox = this.side.element(
      by.css('.attribute input[name="' + attributeName + '"]'));
    filterBox.clear();
    filterBox.sendKeys(filterValue, K.ENTER);
  },

  toggleSampledVisualization: function() {
    this.side.element(by.id('sampled-mode-button')).click();
  },

  toggleBucketedVisualization: function() {
    this.side.element(by.id('bucketed-mode-button')).click();
  },

  undoButton: function() {
    return this.side.element(by.id('undo-button'));
  },

  attributeCount: function() {
    return this.side.all(by.css('li.attribute')).count();
  },

  visualizeAttribute: function(attr, visualization) {
    var e = this.attribute(attr);
    if (visualization === 'x' || visualization === 'y') {
      e.element(by.id('axis-' + visualization + '-' + attr)).click();
    } else {
      e.element(by.id('visualize-as-button')).click();
      e.element(by.id('visualize-as-' + visualization)).click();
    }
  },

  doNotVisualizeAttribute: function(attr, visualization) {
    var e = this.attribute(attr);
    e.element(by.id('do-not-visualize-as-' + visualization)).click();
  },

  attributeSlider: function(attr) {
    var e = this.attribute(attr);
    return e.element(by.id('slider'));
  },

  setSampleRadius: function(radius) {
    var slider = this.side.element(by.id('sample-radius-slider'));
    slider.getAttribute('value').then(function(value) {
      var diff = radius - value;
      while (diff > 0) {
        slider.sendKeys(K.RIGHT);
        diff -= 1;
      }
      while (diff < 0) {
        slider.sendKeys(K.LEFT);
        diff += 1;
      }
    });
  },

  attribute: function(name) {
    return this.side.element(by.id('attribute-' + toID(name)));
  },

  vertexAttribute: function(name) {
    return this.side.element(by.css('.vertex-attribute#attribute-' + toID(name)));
  },

  scalar: function(name) {
    return this.side.element(by.id('scalar-' + toID(name)));
  },

  saveProjectAs: function(newName) {
    this.side.element(by.id('save-as-starter-button')).click();
    this.side.element(by.id('save-as-input')).sendKeys(testLib.selectAllKey + newName);
    this.side.element(by.id('save-as-button')).click();
  },
};

function History(side) {
  this.side = side;
}

History.prototype = {
  open: function() {
    this.side.side.element(by.css('.history-button')).click();
  },

  close: function(discardChanges) {
    if (discardChanges) {
      testLib.expectDialogAndRespond(true);
    }
    this.side.side.element(by.id('close-history-button')).click();
    if (discardChanges) {
      testLib.checkAndCleanupDialogExpectation();
    }
  },

  save: function(name) {
    this.side.side.element(by.css('.save-history-button')).click();
    if (name !== undefined) {
      var inputBox = this.side.side.element(by.css('.save-as-history-box input'));
      inputBox.sendKeys(testLib.selectAllKey + name);
    }
    this.side.side.element(by.css('.save-as-history-box .glyphicon-floppy-disk')).click();
  },

  expectSaveable: function(saveable) {
    expect(this.side.side.element(by.css('.save-history-button')).isPresent()).toBe(saveable);
  },

  // Get an operation from the history. position is a zero-based index.
  getOperation: function(position) {
    var list = this.side.side.all(by.css('project-history div.list-group > li'));
    return list.get(position);
  },

  getOperationName: function(position) {
    return this.getOperation(position).element(by.css('h1')).getText();
  },

  getOperationSegmentation: function(position) {
    return this.getOperation(position).
        element(by.css('div.affected-segmentation')).getText();
  },

  openDropdownMenu: function(operation) {
    var menu = operation.element(by.css('.history-options.dropdown'));
    menu.element(by.css('a.dropdown-toggle')).click();
    return menu;
  },

  clickDropDownMenuItem: function(position, itemId) {
    var operation = this.getOperation(position);
    this.openDropdownMenu(operation).element(by.css('a#dropdown-menu-' + itemId)).click();
  },

  deleteOperation: function(position) {
    this.clickDropDownMenuItem(position, 'discard');
  },

  insertOperation: function(parentPos, direction, name, params, segmentation) {
    var menuItemId = 'add-' + direction;
    if (segmentation) {
      menuItemId += '-for-' + segmentation;
    }
    this.clickDropDownMenuItem(parentPos, menuItemId);
    var newPos = direction === 'up' ? parentPos : parentPos + 1;
    var newOp = this.getOperation(newPos);
    newOp.element(by.id('operation-search')).click();
    newOp.element(by.id('filter')).sendKeys(name, K.ENTER);
    this.side.populateOperation(newOp, params);
    this.side.submitOperation(newOp);
  },

  numOperations: function() {
    return this.side.side.all(by.css('project-history div.list-group > li')).count();
  },

  expectOperationParameter: function(opPosition, paramName, expectedValue) {
    var param = this.getOperation(opPosition).element(by.css('div#' + paramName + ' input'));
    expect(param.getAttribute('value')).toBe(expectedValue);
  }

};

var visualization = {
  svg: element(by.css('svg.graph-view')),

  elementByLabel: function(label) {
    return this.svg.element(by.xpath('.//*[contains(text(),"' + label + '")]/..'));
  },

  clickMenu: function(item) {
    element(by.css('.context-menu #menu-' + item)).click();
  },

  asTSV: function() {
    var copyButton = element(by.css('.graph-sidebar [data-clipboard-text'));
    // It would be too complicated to test actual copy & paste. We just trust ZeroClipboard instead.
    return copyButton.getAttribute('data-clipboard-text');
  },

  // The visualization response received from the server.
  graphView: function() {
    return visualization.svg.evaluate('graph.view');
  },

  // The currently visualized graph data extracted from the SVG DOM.
  graphData: function() {
    browser.waitForAngular();
    return browser.executeScript(function() {

      // Vertices as simple objects.
      function vertexData(svg) {
        var vertices = svg.querySelectorAll('g.vertex');
        var result = [];
        for (var i = 0; i < vertices.length; ++i) {
          var v = vertices[i];
          var touch = v.querySelector('circle.touch');
          var x = touch.getAttribute('cx');
          var y = touch.getAttribute('cy');
          var icon = v.querySelector('path.icon');
          var label = v.querySelector('text');
          var image = v.querySelector('image');
          result.push({
            pos: { x: parseFloat(x), y: parseFloat(y), string: x + ' ' + y },
            label: label.innerHTML,
            icon: image ? null : icon.id,
            color: image ? null : icon.style.fill,
            size: touch.getAttribute('r'),
            opacity: v.getAttribute('opacity'),
            labelSize: label.getAttribute('font-size').slice(0, -2), // Drop "px".
            labelColor: label.style.fill,
            image: image ? image.getAttribute('href') : null,
          });
        }
        result.sort();
        return result;
      }

      // Edges as simple objects.
      function edgeData(svg, vertices) {
        // Build an index by position, so edges can be resolved to vertices.
        var i, byPosition = {};
        for (i = 0; i < vertices.length; ++i) {
          byPosition[vertices[i].pos.string] = i;
        }

        // Collect edges.
        var result = [];
        var edges = svg.querySelectorAll('g.edge');
        function arcStart(d) {
          return d.match(/M (.*? .*?) /)[1];
        }
        for (i = 0; i < edges.length; ++i) {
          var e = edges[i];
          var first = e.querySelector('path.first');
          var second = e.querySelector('path.second');
          var srcPos = arcStart(first.getAttribute('d'));
          var dstPos = arcStart(second.getAttribute('d'));
          result.push({
            src: byPosition[srcPos],
            dst: byPosition[dstPos],
            label: e.querySelector('text').innerHTML,
            color: first.style.stroke,
            width: first.getAttribute('stroke-width'),
          });
        }
        result.sort(function(a, b) {
          return a.src * vertices.length + a.dst - b.src * vertices.length - b.dst;
        });
        return result;
      }

      var svg = document.querySelector('svg.graph-view');
      var vertices = vertexData(svg);
      var edges = edgeData(svg, vertices);
      return { vertices: vertices, edges: edges };
    });
  },

  vertexCounts: function(index) {
    return visualization.graphView().then(function(gv) {
      return gv.vertexSets[index].vertices.length;
    });
  },
};

var splash = {
  project: function(name) {
    return element(by.id('project-' + toID(name)));
  },

  directory: function(name) {
    return element(by.id('directory-' + toID(name)));
  },

  table: function(name) {
    return element(by.id('table-' + toID(name)));
  },

  expectNumProjects: function(n) {
    return expect(element.all(by.css('.project-entry')).count()).toEqual(n);
  },

  expectNumDirectories: function(n) {
    return expect(element.all(by.css('.directory-entry')).count()).toEqual(n);
  },

  expectNumTables: function(n) {
    return expect(element.all(by.css('.table-entry')).count()).toEqual(n);
  },

  openNewProject: function(name) {
    element(by.id('new-project')).click();
    element(by.id('new-project-name')).sendKeys(name, K.ENTER);
    this.hideSparkStatus();
  },

  startTableImport: function(tableName) {
    element(by.id('import-table')).click();
    element(by.css('#import-table #table-name input')).sendKeys(tableName);
  },

  importLocalCSVFile: function(tableName, localCsvFile) {
    this.startTableImport(tableName);
    element(by.css('#datatype select option[value="csv"]')).click();
    var csvFileParameter = element(by.css('#csv-filename file-parameter'));
    testLib.uploadIntoFileParameter(csvFileParameter, localCsvFile);
    var importCsvButton = element(by.id('import-csv-button'));
    // Wait for the upload to finish.
    testLib.wait(protractor.until.elementIsVisible(importCsvButton));
    importCsvButton.click();
  },

  newDirectory: function(name) {
    element(by.id('new-directory')).click();
    element(by.id('new-directory-name')).sendKeys(name, K.ENTER);
  },

  openProject: function(name) {
    this.project(name).click();
    this.hideSparkStatus();
  },

  hideSparkStatus: function() {
    // Floating elements can overlap buttons and block clicks.
    browser.executeScript(
      'document.styleSheets[0].insertRule(\'.spark-status { position: static !important; }\');');
  },

  openDirectory: function(name) {
    this.directory(name).click();
  },

  popDirectory: function() {
    element(by.id('pop-directory-icon')).click();
  },

  menuClick: function(entry, action) {
    var menu = entry.element(by.css('.dropdown'));
    menu.element(by.css('a.dropdown-toggle')).click();
    menu.element(by.id('menu-' + action)).click();
  },

  renameProject: function(name, newName) {
    var project = this.project(name);
    this.menuClick(project, 'rename');
    project.element(by.id('renameBox')).sendKeys(testLib.selectAllKey, newName, K.ENTER);
  },

  deleteProject: function(name) {
    this.menuClick(this.project(name), 'discard');
    // We need to give the browser time to display the alert, see angular/protractor#1486.
    testLib.wait(protractor.ExpectedConditions.alertIsPresent());
    var confirmation = browser.switchTo().alert();
    expect(confirmation.getText()).toContain('delete project ');
    expect(confirmation.getText()).toContain(name);
    confirmation.accept();
  },

  deleteDirectory: function(name) {
    this.menuClick(this.directory(name), 'discard');
    // We need to give the browser time to display the alert, see angular/protractor#1486.
    testLib.wait(protractor.ExpectedConditions.alertIsPresent());
    var confirmation = browser.switchTo().alert();
    expect(confirmation.getText()).toContain('delete directory ' + name);
    confirmation.accept();
  },

  expectProjectListed: function(name) {
    testLib.expectElement(this.project(name));
  },

  expectProjectNotListed: function(name) {
    testLib.expectNotElement(this.project(name));
  },

  expectDirectoryListed: function(name) {
    testLib.expectElement(this.directory(name));
  },

  expectDirectoryNotListed: function(name) {
    testLib.expectNotElement(this.directory(name));
  },

  expectTableListed: function(name) {
    testLib.expectElement(this.table(name));
  },

  expectTableNotListed: function(name) {
    testLib.expectNotElement(this.table(name));
  },

  enterSearchQuery: function(query) {
    element(by.id('project-search-box')).sendKeys(testLib.selectAllKey + query);
  },

  clearSearchQuery: function() {
    element(by.id('project-search-box')).sendKeys(testLib.selectAllKey + K.BACK_SPACE);
  },
};

function randomPattern () {
  /* jshint bitwise: false */
  var crypto = require('crypto');
  var buf = crypto.randomBytes(16);
  var sixteenLetters = 'abcdefghijklmnop';
  var r = '';
  for (var i = 0; i < buf.length; i++) {
    var v = buf[i];
    var lo =  (v & 0xf);
    var hi = (v >> 4);
    r += sixteenLetters[lo] + sixteenLetters[hi];
  }
  return r;
}


testLib = {
  theRandomPattern: randomPattern(),
  left: new Side('left'),
  right: new Side('right'),
  visualization: visualization,
  splash: splash,
  selectAllKey: K.chord(K.CONTROL, 'a'),

  expectElement: function(e) {
    expect(e.isDisplayed()).toBe(true);
  },

  expectNotElement: function(e) {
    expect(e.isPresent()).toBe(false);
  },

  // Deletes all projects and directories.
  discardAll: function() {
    var defer = protractor.promise.defer();
    request.post(
      browser.baseUrl + 'ajax/discardAllReallyIMeanIt',
      { json: { fake: 1 } },
      function(error, message) {
        if (error || message.statusCode >= 400) {
          defer.reject({ error : error, message : message });
        } else {
          defer.fulfill();
        }
      });
    browser.controlFlow().execute(function() { return defer.promise; });
  },

  navigateToProject: function(name) {
    browser.get('/#/project/' + name);
  },

  helpPopup: function(helpId) {
    return element(by.css('div[help-id="' + helpId + '"]'));
  },

  openNewProject: function(name) {
    element(by.id('new-project')).click();
    element(by.id('new-project-name')).sendKeys(name, K.ENTER);
  },

  sendKeysToACE: function(e, keys) {
    var aceContent = e.element(by.css('div.ace_content'));
    var aceInput = e.element(by.css('textarea.ace_text-input'));
    // The double click on the text area focuses it properly.
    browser.actions().doubleClick(aceContent).perform();
    aceInput.sendKeys(testLib.selectAllKey + keys);
  },

  setParameter: function(e, value) {
    // Special parameter types need different handling.
    e.evaluate('param.kind').then(
        function(kind) {
          if (kind === 'code') {
            testLib.sendKeysToACE(e, testLib.selectAllKey + value);
          } else if (kind === 'file') {
            testLib.uploadIntoFileParameter(e, value)
          } else if (kind === 'tag-list') {
            var values = value.split(',');
            for (var i = 0; i < values.length; ++i) {
              e.element(by.css('.dropdown-toggle')).click();
              e.element(by.css('.dropdown-menu #' + values[i])).click();
            }
          } else if (kind === 'table') {
            // Table name options look like 'name of table (date of table creation)'.
            // The date is unpredictable, but we are going to match to the ' (' part
            // to minimize the chance of mathcing an other table.
            var optionLabelPattern = value + ' (';
            e.element(by.cssContainingText('option', optionLabelPattern)).click();
          } else {
            e.sendKeys(testLib.selectAllKey + value);
          }
        });
  },

  // Expects a window.confirm call from the client code and overrides the user
  // response.
  expectDialogAndRespond: function(responseValue) {
    // I am not particularly happy with this solution. The problem with the nice
    // solution is that there is a short delay before the alert actually shows up
    // and protractor does not wait for it. (Error: NoSuchAlertError: no alert open)
    // See: https://github.com/angular/protractor/issues/1486
    // Other possible options:
    // 1. browser.wait for the alert to appear. This introduces a hard timout
    // and potential flakiness.
    // 2. Use Jasmine's spyOn. The difficulty there is in getting hold of a
    // window object from inside the browser, if at all ppossible.
    // 3. Use a mockable Angular module for window.confirm from our app.
    browser.executeScript(
        'window.confirm0 = window.confirm;' +
        'window.confirm = function() {' +
        '  window.confirm = window.confirm0;' +
        '  return ' + responseValue+ ';' +
        '}');
  },

  checkAndCleanupDialogExpectation: function() {
    // Fail if there was no alert.
    expect(browser.executeScript('return window.confirm === window.confirm0')).toBe(true);
    browser.executeScript('window.confirm = window.confirm0;');
  },

  // Warning, this also sorts the given array parameter in place.
  sortHistogramValues: function(values) {
    return values.sort(function(b1, b2) {
      if (b1.title < b2.title) {
        return -1;
      } else if (b1.title > b2.title) {
        return 1;
      } else {
        return 0;
      }
    });
  },

  // A promise of the list of error messages.
  errors: function() {
    return element.all(by.css('.top-alert-message')).map(function(e) { return e.getText(); });
  },

  // Expects that there will be a single error message and returns it as a promise.
  error: function() {
    return testLib.errors().then(function(errors) {
      expect(errors.length).toBe(1);
      return errors[0];
    });
  },

  closeErrors: function() {
    element.all(by.css('.top-alert')).each(function(e) {
      e.element(by.id('close-alert-button')).click();
    });
  },

  // Wait indefinitely.
  // WebDriver 2.45 changed browser.wait() to default to a 0 timeout. This was reverted in 2.46.
  // But the current Protractor version uses 2.45, so we have this wrapper.
  wait: function(condition) {
    browser.wait(condition, 99999999);
  },

  expectModal: function(title) {
    var t = element(by.css('.modal-title'));
    testLib.expectElement(t);
    expect(t.getText()).toEqual(title);
  },

  closeModal: function() {
    element(by.id('close-modal-button')).click();
  },

  setEnablePopups: function(enable) {
    browser.executeScript(
      "angular.element(document.body).injector()" +
      ".get('dropTooltipConfig').enabled = " + enable);

  },

  uploadIntoFileParameter: function(fileParameterElement, fileName) {
    var input = fileParameterElement.element(by.id('file'));
    // Need to unhide flowjs's secret file uploader.
    browser.executeScript(
      function(input) {
        input.style.visibility = 'visible';
        input.style.height = '1px';
        input.style.width = '1px';
        input.style.opacity = 1;
      },
      input.getWebElement());
    input.sendKeys(fileName);
  },
};

module.exports = testLib;
