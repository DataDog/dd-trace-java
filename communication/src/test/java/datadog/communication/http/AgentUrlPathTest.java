package datadog.communication.http;

import static datadog.communication.http.OkHttpUtils.appendPath;
import static org.junit.jupiter.api.Assertions.assertEquals;

import okhttp3.HttpUrl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentUrlPathTest {
  @ParameterizedTest
  @ValueSource(strings = {"/agent-prefix", "/agent-prefix/", "/agent%2Fprefix/"})
  void preservesPrefixForDiscoveryAndDelivery(String prefix) {
    final HttpUrl base = HttpUrl.get("http://localhost:8126" + prefix);
    final String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
    assertEquals(normalized + "info", appendPath(base, "info").encodedPath());
    assertEquals(normalized + "evp_proxy/v4/", appendPath(base, "/evp_proxy/v4/").encodedPath());
    assertEquals(normalized + "evp_proxy/v2/", appendPath(base, "evp_proxy/v2/").encodedPath());
  }
}
