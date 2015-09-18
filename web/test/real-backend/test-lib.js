'use strict';

/* global element */
/* global by */
/* global protractor */

module.exports = (function() {
  var K = protractor.Key;  // Short alias.
  return {
    evaluateOnLeftSide: function(expr) {
      return element(by.css('#side-left')).evaluate(expr);
    },

    expectCurrentProjectIs: function(name) {
      expect(browser.getCurrentUrl()).toContain('/#/project/' + name);
    },

    leftApplyFilters: function() {
      return element(by.css('#side-left #apply-filters-button')).click();
    },

    leftEdgeCount: function() {
      var asStr = element(by.css('#side-left value#edge-count span.value')).getText();
      return asStr.then(function(asS) { return parseInt(asS); });
    },

    leftVertexCount: function() {
      var asStr = element(by.css('#side-left value#vertex-count span.value')).getText();
      return asStr.then(function(asS) { return parseInt(asS); });
    },

    segmentCount: function() {
      var asStr = element(by.css('#side-right value#segment-count span.value')).getText();
      return asStr.then(function(asS) { return parseInt(asS); });
    },


    openNewProject: function(name) {
      element(by.id('new-project')).click();
      element(by.id('new-project-name')).sendKeys(name, K.ENTER);
    },

    openSegmentation: function(segmentationName) {
      var s = '#side-left .segmentation #segmentation-' + segmentationName;
      element(by.css(s)).click();
    },

    runLeftOperation: function(name, params) {
      params = params || {};
      element(by.css('#operation-toolbox-left #operation-search')).click();
      element(by.css('#operation-toolbox-left #filter')).sendKeys(name, K.ENTER);

      for (var key in params) {
        var p = '#operation-toolbox-left operation-parameters #' + key + ' > *';
        element(by.css(p)).sendKeys(params[key], K.ENTER);
      }

      element(by.css('#operation-toolbox-left .ok-button')).click();
    },

    setLeftAttributeFilter: function(attributeName, filterValue) {
      var sel = '#side-left .attribute input[name="' + attributeName + '"]' ;
      console.log(sel);
      var filterBox = element(
        by.css(sel));
      filterBox.sendKeys(filterValue, K.ENTER);    
    },
  };
})();
