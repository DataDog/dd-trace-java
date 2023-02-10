import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import datadog.trace.test.util.Flaky
import spock.lang.Timeout

@Flaky
@Timeout(5)
class GoogleHttpClientAsyncTest extends AbstractGoogleHttpClientTest {
  @Override
  HttpResponse executeRequest(HttpRequest request) {
    return request.executeAsync().get()
  }
}
