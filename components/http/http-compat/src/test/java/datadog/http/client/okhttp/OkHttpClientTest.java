package datadog.http.client.okhttp;

import datadog.http.client.HttpClientTest;
import datadog.http.client.HttpProviders;
import org.junit.jupiter.api.BeforeAll;

public class OkHttpClientTest extends HttpClientTest {
  @BeforeAll
  static void forceOkHttpClient() {
    HttpProviders.forceCompatClient();
  }
}
