'use strict';

angular.module('biggraph')
  .controller('MetaGraphViewCtrl', function ($scope, $resource, $modal, $location, util) {
    function defaultSideState() {
      return {
        vertexSet: undefined,
        edgeBundle: undefined,
        filters: {},
        graphMode: undefined,
        bucketCount: 4,
        sampleRadius: 1,
        centers: ['0'],
      };
    }
    function defaultState() {
      return {
        leftToRightPath: undefined,
        left: defaultSideState(),
        right: defaultSideState(),
      };
    }

    function setState(state) {
      $scope.state = state;
      $scope.state.left.setCenter = function(id) { $scope.state.left.centers = [id]; };
      $scope.state.right.setCenter = function(id) { $scope.state.right.centers = [id]; };
    }

    setState(defaultState());

    $scope.left = {};
    $scope.right = {};

    $scope.left.other = $scope.right;
    $scope.right.other = $scope.left;

    util.deepWatch(
      $scope,
      function() { return $location.search(); },
      function() {
        if (!$location.search().q) {
          setState(defaultState());
        } else {
          var state = JSON.parse($location.search().q);
          if (!angular.equals(state, $scope.state)) {
            // The parts of the template that depend on 'state' get re-rendered
            // when we replace it. So we only do this if there is an actual
            // difference.
            setState(state);
          }
        }
      });

    util.deepWatch(
      $scope,
      'state',
      function() {
        var s = $location.search();
        s.q = JSON.stringify($scope.state);
        $location.search(s);
      });

    function loadVertexSet(id) {
      return util.nocache('/ajax/vertexSet', { id: id });
    }

    $scope.$watch(
      'state.left.vertexSet',
      function() {
        var leftVS = $scope.state.left.vertexSet;
        $scope.left.vertexSet = leftVS;
        if (leftVS !== undefined) {
          $scope.left.data = loadVertexSet(leftVS.id);
        } else {
          $scope.left.data = undefined;
        }
      });
    $scope.$watch(
      'state.right.vertexSet',
      function() {
        var rightVS = $scope.state.right.vertexSet;
        $scope.right.vertexSet = rightVS;
        if (rightVS !== undefined) {
          $scope.right.data = loadVertexSet(rightVS.id);
        } else {
          $scope.right.data = undefined;
        }
      });

    function loadStartingVertexSets() {
      $scope.startingVertexSets = util.nocache('/ajax/startingVs', undefined, { isArray: true });
    }

    function openOperationModal(operation) {
      var modalInstance = $modal.open({
        templateUrl: 'views/operationParameters.html',
        controller: 'OperationParametersCtrl',
        backdrop: 'static',  // Do not close on backdrop click.
        resolve: {
          operation: function() {
            return operation;
          }
        }
      });
      return modalInstance.result;
    }

    function applyOperation(operation, modalResult) {
      var request = {
        id: operation.id,
        parameters: modalResult
      };
      util.post('/ajax/applyOp',
        request,
        function() {
          // Force reloads of graphs by creating new objects.
          var oldLeftVS = $scope.state.left.vertexSet;
          if (oldLeftVS !== undefined) {
            $scope.state.left.vertexSet = { id: oldLeftVS.id };
          }
          var oldRightVS = $scope.state.right.vertexSet;
          if (oldRightVS !== undefined) {
            $scope.state.right.vertexSet = { id: oldRightVS.id };
          }
          loadStartingVertexSets();
        });
    }

    function applyOperationFlow(operation) {
      openOperationModal(operation).then(function(modalResult) {
        applyOperation(operation, modalResult);
      });
    }

    $scope.startingOps = util.nocache('/ajax/startingOps', undefined, { isArray: true });
    loadStartingVertexSets();

    $scope.apply = applyOperationFlow;
    $scope.removePath = function() {
      $scope.state.leftToRightPath = undefined;
    };

    $scope.left.state = function() {
      return $scope.state.left;
    };
    $scope.right.state = function() {
      return $scope.state.right;
    };
    $scope.setState = function(side, setting, value) {
      if (side.state()[setting] === value) {
        // Clicking the same attribute setting again turns it off.
        delete side.state()[setting];
      } else {
        side.state()[setting] = value;
      }
    };

    $scope.left.name = 'left';
    $scope.right.name = 'right';

    // Creates a new side state, while retaining the settings where this makes sense.
    function freshSideState(old) {
      var fresh = defaultSideState();
      fresh.graphMode = old.graphMode;
      fresh.sampleRadius = old.sampleRadius;
      fresh.bucketCount = old.bucketCount;
      return fresh;
    }
    $scope.left.setVS = function(id) {
      $scope.state.left = freshSideState($scope.state.left);
      $scope.state.left.vertexSet = { id: id };
    };
    $scope.right.setVS = function(id) {
      $scope.state.right = freshSideState($scope.state.right);
      $scope.state.right.vertexSet = { id: id };
    };

    $scope.mirror = function(side) {
      $scope.state.leftToRightPath = [];
      side.other.setVS(side.data.id);
    };
    
    $scope.left.setState = function(state) {
      $scope.state.left = state($scope.state.left);
    };
    $scope.right.setState = function(state) {
      $scope.state.right = state($scope.state.right);
    };

    $scope.close = function(side) {
      $scope.removePath();      
      side.setState(defaultSideState);
    };

    function setNewVS(side) {
      return function(id) {
        $scope.removePath();
        side.setVS(id);
      };
    }
    $scope.left.setNewVS = setNewVS($scope.left);
    $scope.right.setNewVS = setNewVS($scope.right);

    $scope.left.addEBToPath = function(bundle, pointsTowardsMySide) {
      $scope.state.leftToRightPath.unshift({bundle: bundle, pointsLeft: pointsTowardsMySide});
    };
    $scope.right.addEBToPath = function(bundle, pointsTowardsMySide) {
      $scope.state.leftToRightPath.push({bundle: bundle, pointsLeft: !pointsTowardsMySide});
    };

    function followEB(side) {
      return function(bundle, pointsTowardsMySide) {
        if ($scope.state.leftToRightPath !== undefined) {
          side.addEBToPath(bundle, pointsTowardsMySide);
        }
        var oldFilters = side.state().filters;
        if (pointsTowardsMySide) {
          side.setVS(bundle.destination.id);
        } else {
          side.setVS(bundle.source.id);
        }
        // Keep filters when following local edges.
        if (bundle.destination.id === bundle.source.id) {
          side.state().filters = oldFilters;
        }
      };
    }
    $scope.left.followEB = followEB($scope.left);
    $scope.right.followEB = followEB($scope.right);

    function showEB(side) {
      return function(bundle, pointsTowardsMySide) {
        $scope.state.leftToRightPath = [];
        side.addEBToPath(bundle, pointsTowardsMySide);
        if (pointsTowardsMySide) {
          side.other.setVS(bundle.source.id);
        } else {
          side.other.setVS(bundle.destination.id);
        }
      };
    }
    $scope.left.showEB = showEB($scope.left);
    $scope.right.showEB = showEB($scope.right);

    $scope.cutPathLeft = function(idx) {
      $scope.state.leftToRightPath.splice(0, idx);
      var firstStep = $scope.state.leftToRightPath[0];
      if (firstStep.pointsLeft) {
        $scope.left.setVS(firstStep.bundle.destination.id);
      } else {
        $scope.left.setVS(firstStep.bundle.source.id);
      }
    };
    $scope.cutPathRight = function(idx) {
      $scope.state.leftToRightPath.splice(idx + 1);
      var lastStep = $scope.state.leftToRightPath[$scope.state.leftToRightPath.length - 1];
      if (lastStep.pointsLeft) {
        $scope.right.setVS(lastStep.bundle.source.id);
      } else {
        $scope.right.setVS(lastStep.bundle.destination.id);
      }
    };
  });
