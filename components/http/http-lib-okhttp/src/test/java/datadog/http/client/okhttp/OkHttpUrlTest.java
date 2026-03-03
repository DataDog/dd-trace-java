package datadog.http.client.okhttp;

import datadog.http.client.HttpProviders;
import datadog.http.client.HttpUrlTest;
import org.junit.jupiter.api.BeforeAll;

public class OkHttpUrlTest extends HttpUrlTest {
  @BeforeAll
  static void forceOkHttpClient() {
    HttpProviders.forceCompatClient();
  }
}
