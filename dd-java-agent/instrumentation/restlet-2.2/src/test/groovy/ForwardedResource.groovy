import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.data.Form
import org.restlet.resource.Get
import org.restlet.resource.ServerResource

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED

class ForwardedResource extends ServerResource {
  @Get("txt")
  String forwarded() {
    HttpServerTest.controller(FORWARDED) {
      Form headers = (Form) request.getAttributes().get("org.restlet.http.headers")
      // Restlet capitalizes the first character of headers
      return headers.getFirstValue("X-forwarded-for")
    }
  }
}
