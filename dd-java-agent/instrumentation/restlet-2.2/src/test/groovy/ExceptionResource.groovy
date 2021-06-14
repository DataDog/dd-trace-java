import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION

class ExceptionResource extends ServerResource {
  @Get("txt")
  String exception() {
    HttpServerTest.controller(EXCEPTION) {
      throw new Exception(EXCEPTION.body)
    }
  }
}
