import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.Timeout

abstract class GoogleHttpClientTest extends AbstractGoogleHttpClientTest {

  @Override
  HttpResponse executeRequest(HttpRequest request) {
    return request.execute()
  }
}

@Timeout(5)
class GoogleHttpClientV0ForkedTest extends GoogleHttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

@Timeout(5)
class GoogleHttpClientV1ForkedTest extends GoogleHttpClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}
