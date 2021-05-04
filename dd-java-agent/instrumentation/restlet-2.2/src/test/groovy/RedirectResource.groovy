import datadog.trace.agent.test.base.HttpServerTest
import org.restlet.resource.Get
import org.restlet.resource.ServerResource
import org.restlet.routing.Redirector

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT

class RedirectResource extends ServerResource {
  @Get("txt")
  String redirect() {
    HttpServerTest.controller(REDIRECT) {
      Redirector redirector = new Redirector(getContext(), getRootRef().toString() + REDIRECT.body, Redirector.MODE_CLIENT_FOUND)
      redirector.handle(getRequest(), getResponse())
      return null
    }
  }
}
