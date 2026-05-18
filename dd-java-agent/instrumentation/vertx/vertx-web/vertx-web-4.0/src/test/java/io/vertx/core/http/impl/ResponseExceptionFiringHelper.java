package io.vertx.core.http.impl;

import io.vertx.core.http.HttpServerResponse;

/**
 * Test-side bridge that fires the package-private {@code Http1xServerResponse.handleException} on a
 * Vert.x 4.x server response. Used by {@code server.RouteHandlerExceptionHandlerTest} to
 * deterministically reproduce the non-{@code CLOSED_EXCEPTION} I/O-failure path that Vert.x exposes
 * via {@code response.exceptionHandler(...)}.
 */
public final class ResponseExceptionFiringHelper {
  private ResponseExceptionFiringHelper() {}

  public static void fireException(HttpServerResponse response, Throwable cause) {
    ((Http1xServerResponse) response).handleException(cause);
  }
}
