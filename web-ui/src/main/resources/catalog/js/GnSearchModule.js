(function() {
  goog.provide('gn_search');






  goog.require('gn_map_field_directive');
  goog.require('gn_module');
  goog.require('gn_resultsview');
  goog.require('gn_search_controller');
  goog.require('gn_viewer');

  var module = angular.module('gn_search', [
    'gn_module',
    'gn_resultsview',
    'gn_map_field_directive',
    'gn_search_controller',
    'gn_viewer',
    'ui.bootstrap.buttons',
    'ui.bootstrap.tabs',
    'ngeo'
  ]);

  module.constant('gnSearchSettings', {});
  module.constant('gnViewerSettings', {});

  module.config(['$LOCALES', function($LOCALES) {
    $LOCALES.push('search');
  }]);

})();
