import static datadog.environment.OperatingSystem.isArm64
import static datadog.environment.OperatingSystem.isLinux

import org.restlet.Request
import org.restlet.Response
import org.restlet.data.Header
import org.restlet.routing.Filter
import org.restlet.util.Series

class RestletTest extends RestletTestBase {

  @Override
  boolean testParallelRequest() {
    // TODO: Parallel processing is failing on Linux arm64.
    return !(isLinux() && isArm64())
  }

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
