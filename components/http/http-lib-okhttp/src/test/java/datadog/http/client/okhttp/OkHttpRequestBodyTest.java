package datadog.http.client.okhttp;

import datadog.http.client.HttpProviders;
import datadog.http.client.HttpRequestBodyTest;
import org.junit.jupiter.api.BeforeAll;

public class OkHttpRequestBodyTest extends HttpRequestBodyTest {
  @BeforeAll
  static void forceOkHttpClient() {
    HttpProviders.forceCompatClient();
  }
}
