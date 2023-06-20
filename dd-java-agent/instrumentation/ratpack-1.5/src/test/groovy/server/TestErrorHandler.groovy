package server

import ratpack.error.ServerErrorHandler
import ratpack.handling.Context

class TestErrorHandler implements ServerErrorHandler {
  @Override
  void error(Context context, Throwable throwable) throws Exception {
    context.response.status(500).send(throwable.message)
  }
}
