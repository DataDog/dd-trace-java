package server

import datadog.trace.agent.test.base.HttpServerTest
import ratpack.handling.Context
import ratpack.handling.Handler

class ResponseHeaderDecorator implements Handler {

  @Override
  void handle(final Context context) throws Exception {
    context.response.headers.set(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
    context.next()
  }
}
