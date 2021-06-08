import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class SuccessResource extends ServerResource {
  @Get("txt")
  String success() {
    HttpServerTest.controller(SUCCESS) {
      return SUCCESS.body
    }
  }
}
