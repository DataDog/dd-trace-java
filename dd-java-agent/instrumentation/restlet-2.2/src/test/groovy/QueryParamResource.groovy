import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM

class QueryParamResource extends ServerResource {
  @Get("txt")
  String query_param() {
    HttpServerTest.controller(QUERY_PARAM) {
      String result = getQuery().toString()
      return result.substring(2,result.length()-2)
    }
  }
}
