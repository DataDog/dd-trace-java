import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.test.util.Flaky
import spock.lang.Timeout

@Flaky
@Timeout(5)
class GoogleHttpClientAsyncTest extends AbstractGoogleHttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Override
  String operation() {
    "google-http-client.request"
  }

  @Override
  HttpResponse executeRequest(HttpRequest request) {
    return request.executeAsync().get()
  }
}
