package datadog.http.client.okhttp;

import datadog.http.client.HttpClientAsyncTest;
import datadog.http.client.HttpProviders;
import org.junit.jupiter.api.BeforeAll;

public class OkHttpClientAsyncTest extends HttpClientAsyncTest {
  @BeforeAll
  static void forceOkHttpClient() {
    HttpProviders.forceCompatClient();
  }
}
