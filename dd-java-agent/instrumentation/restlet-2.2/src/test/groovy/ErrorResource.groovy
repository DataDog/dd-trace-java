import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.data.Status
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR

class ErrorResource extends ServerResource {
  @Get("txt")
  String error() {
    HttpServerTest.controller(ERROR) {
      setStatus(new Status(ERROR.status), ERROR.body)
      return ERROR.body
    }
  }
}
