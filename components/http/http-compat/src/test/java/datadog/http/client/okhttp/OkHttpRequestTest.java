package datadog.http.client.okhttp;

import datadog.http.client.HttpProviders;
import datadog.http.client.HttpRequestTest;
import org.junit.jupiter.api.BeforeAll;

/**
 * Test for OkHttpRequest using shared test fixtures.
 */
public class OkHttpRequestTest extends HttpRequestTest {
  @BeforeAll
  static void forceOkHttpClient() {
    HttpProviders.forceCompatClient();
  }
}
