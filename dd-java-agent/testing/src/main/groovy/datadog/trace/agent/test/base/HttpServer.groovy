package datadog.trace.agent.test.base

import java.util.concurrent.TimeoutException

interface HttpServer {

  void start() throws TimeoutException

  void stop()

  URI address()
}
