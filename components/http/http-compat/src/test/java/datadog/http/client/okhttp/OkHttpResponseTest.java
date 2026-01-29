package datadog.http.client.okhttp;

import datadog.http.client.HttpProviders;
import datadog.http.client.HttpResponseTest;
import org.junit.jupiter.api.BeforeAll;

/**
 * Test for OkHttpResponse using shared test fixtures.
 */
public class OkHttpResponseTest extends HttpResponseTest {
  @BeforeAll
  static void forceOkHttpClient() {
    HttpProviders.forceCompatClient();
  }
}
