package org.mule.runtime.api.util

import static datadog.trace.agent.test.base.HttpServerTest.controller
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint

/**
 * Bridge class to allow the {@code HttpServerTestHandler} inside the mule application
 * to interact with the {@code HttpServerTest} code.
 *
 * We need to have this bridge class in a proper exported mule package, to be
 * accessible from inside the {@code HttpServerTestHandler} code.
 */
class HttpServerTestBridge {
  static Object[] testHandle(String requestPath) {
    ServerEndpoint endpoint = ServerEndpoint.forPath(requestPath)
    if (endpoint == ServerEndpoint.EXCEPTION) {
      controller(endpoint) {
        throw new Exception(endpoint.body)
      }
    }
    return controller(endpoint) {
      return [endpoint.body, endpoint.status] as Object[]
    }
  }
}
