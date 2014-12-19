'use strict';

var loginScope;
angular.module('biggraph')
  .controller('LoginCtrl', function ($scope, $location, util) {
    loginScope = $scope;
    $scope.credentials = { username: '', password: '' };
    // OAuth is only set up on pizzakite.lynxanalytics.com.
    $scope.passwordSignIn = window.location.hostname !== 'pizzakite.lynxanalytics.com';

    function tryLogin(url, credentials) {
      util.post(url, credentials).then(function(success) {
        $scope.submitted = false;
        if (success) {
          $location.url('/');
        }
      });
      $scope.submitted = true;
    }

    $scope.passwordLogin = function() {
      tryLogin('/passwordLogin', $scope.credentials);
    };

    $scope.googleLogin = function(code) {
      if (!code) { return; }
      tryLogin('/googleLogin', { code: code });
    };
  });

/* exported googleSignInCallback */
function googleSignInCallback(authResult) {
  loginScope.googleLogin(authResult.code);
}
