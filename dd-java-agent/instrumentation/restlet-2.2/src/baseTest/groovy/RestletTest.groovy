import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import org.restlet.Request
import org.restlet.Response
import org.restlet.engine.header.Header
import org.restlet.routing.Filter
import org.restlet.util.Series

abstract class RestletTest extends RestletTestBase {

  @Override
  protected Filter createHeaderFilter() {
    return new ResponseHeaderFilter()
  }

  private static class ResponseHeaderFilter extends Filter {
    @Override
    protected void afterHandle(Request request, Response response) {
      def attributes = response.getAttributes()
      Series<Header> headers = attributes.get('org.restlet.http.headers')
      if (headers == null) {
        headers = new Series<>(Header)
        attributes.put('org.restlet.http.headers', headers)
      }
      headers.add(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
      super.afterHandle(request, response)
    }
  }
}

class RestletV0ForkedTest extends RestletTest {
  //V0 expected operation already defined on the base test class
}

class RestletV1ForkedTest extends RestletTest implements TestingGenericHttpNamingConventions.ServerV1 {
}
