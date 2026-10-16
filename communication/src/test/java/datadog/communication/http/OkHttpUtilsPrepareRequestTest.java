package datadog.communication.http;

import static datadog.communication.http.OkHttpUtils.DATADOG_CONTAINER_ID;
import static datadog.communication.http.OkHttpUtils.DATADOG_EXTERNAL_ENV;
import static datadog.communication.http.OkHttpUtils.prepareRequest;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

class OkHttpUtilsPrepareRequestTest {

  private static final HttpUrl URL = HttpUrl.get("http://localhost:8126/v0.4/traces");
  private static final String EXTERNAL_ENV =
      "it-false,cn-nginx-webserver,pu-75a2b6d5-3949-4afb-ad0d-92ff0674e759";

  @Test
  void addsExternalEnvHeaderWhenSet() {
    Request request =
        prepareRequest(URL, emptyMap(), "containerId", "entityId", EXTERNAL_ENV).get().build();

    assertEquals(EXTERNAL_ENV, request.header(DATADOG_EXTERNAL_ENV));
    assertEquals("containerId", request.header(DATADOG_CONTAINER_ID));
  }

  @Test
  void omitsExternalEnvHeaderWhenUnset() {
    Request request = prepareRequest(URL, emptyMap(), null, null, null).get().build();

    assertNull(request.header(DATADOG_EXTERNAL_ENV));
  }
}
