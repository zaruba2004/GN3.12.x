/*
 * Copyright (C) 2001-2016 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */

(function() {
  goog.provide('gn_dutch_gazetteer_factory');

  var module = angular.module('gn_dutch_gazetteer_factory', []);

  module.provider('gnDutchGazetteer', function() {
    return {
      $get : [
        '$http',
        'gnGlobalSettings',
        'gnViewerSettings',
        'gnGetCoordinate',
        function($http, gnGlobalSettings, gnViewerSettings, gnGetCoordinate) {
          var zoomTo = function(extent, map, zoom) {
            // fit geometry
            map.getView().fit(extent, map.getSize());
            // zoom in
            if (zoom) {
              map.getView().setZoom(zoom);
            }          
          }
          return {
            onClick: function(scope, loc, map) {
            	// get the details from the lookup service
              var url = 'https://address-services.nca.by/belgeodezia/api/address/'+loc.id;
              $http.get(url).
              success(function(response) {

                if (response.result.length) {
                	var dataPointY = response.result[0].ycoord;
                  var dataPointX = response.result[0].xcoord;
                	var mapProjection = map.getView().getProjection().getCode();
                  // turn it into a geometry and convert
                  var geom = new ol.format.WKT().readGeometry('POINT('+dataPointY+' '+dataPointX+')').transform('EPSG:4326', mapProjection);
                  // zoom to the coordinates
                  // 
                  // zoom depends on type
                  var zoom = 13;
                  var type = response.result[0].houseN;

                  if (type != null) {
                    zoom = 17;
                  // } else if (type == 'woonplaats') {
                  //   zoom = 9;
                  // } else if (type == 'weg' || type == 'postcode') {
                  //   zoom = 12;
                  // } else if (type == 'adres' || type == 'hectometerpaal' || type == 'perceel') {
                  //   zoom = 13;
                  }

                  // fit the geometry and zoom
                  zoomTo(geom, map, zoom);
                  scope.query = loc.name;
                  scope.collapsed = true;
                }
              });
            },
            search: function(scope, loc, query) {
              if (query.length < 3) return;

              var coord = gnGetCoordinate(
                scope.map.getView().getProjection().getWorldExtent(), query);

              if (coord) {
                function moveTo(map, zoom, center) {
                  var view = map.getView();

                  view.setZoom(zoom);
                  view.setCenter(center);
                }
                moveTo(scope.map, 5, ol.proj.transform(coord,
                    'EPSG:4326', 'EPSG:28992'));
                return;
              }
              var formatter = function(loc) {
                var props = [];
                ['toponymName', 'adminName1', 'countryName'].
                    forEach(function(p) {
                      if (loc[p]) { props.push(loc[p]); }
                    });
                return (props.length == 0) ? '' : '—' + props.join(', ');
              };
              var url = 'https://address-services.nca.by/belgeodezia/api/address/searchAddressByString';
              $http.post(url, {
                arguments: query
              }).
              success(function(response) {
                // array for the search results
                scope.results = [];
                // no results, just stop, don't build the dropdown
                var resultLeng = response.result.length;

                if (resultLeng == 0) {
                  return;
                }
                // get the results
                $features = response.result;
                // loop through the results
                $.each($features, function(i, item) {
                  // create the result
                  scope.results.push({
                    id: item.code,
                    name: item.shortName,
                    type: item.type
                    // score: item.score
                  });
                });
              });
            }
          }
        }]
    }
  });
})();