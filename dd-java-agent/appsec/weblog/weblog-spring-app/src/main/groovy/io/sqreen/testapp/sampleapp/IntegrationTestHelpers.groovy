package io.sqreen.testapp.sampleapp

import javax.servlet.http.HttpServletRequest

class IntegrationTestHelpers {
  static boolean isInteTestRequest(HttpServletRequest request) {
    (request.getHeader('User-agent') ?: '').contains('python-requests')
  }
}
