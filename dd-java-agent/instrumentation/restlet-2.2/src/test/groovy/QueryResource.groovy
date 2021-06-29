import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.forPath

abstract class QueryResource extends ServerResource {
  String handleRequest() {
    HttpServerTest.ServerEndpoint endpoint = forPath(getReference().path)
    HttpServerTest.controller(endpoint) {
      return endpoint.bodyForQuery(getReference().query)
    }
  }
}

class QueryEncodedBothResource extends QueryResource {
  @Get("txt")
  String query_encoded_both() {
    return handleRequest()
  }
}

class QueryEncodedQueryResource extends QueryResource {
  @Get("txt")
  String query_encoded_query() {
    return handleRequest()
  }
}

class QueryParamResource extends QueryResource {
  @Get("txt")
  String query_param() {
    return handleRequest()
  }
}
