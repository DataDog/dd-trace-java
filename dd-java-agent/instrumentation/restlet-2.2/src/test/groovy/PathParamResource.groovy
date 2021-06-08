import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM

class PathParamResource extends ServerResource {
  @Get("txt")
  String path_param() {
    HttpServerTest.controller(PATH_PARAM) {
      return getAttribute("id")
    }
  }
}
