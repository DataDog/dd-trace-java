package datadog.communication.http;

import static datadog.communication.http.OkHttpUtils.DATADOG_CONTAINER_ID;
import static datadog.communication.http.OkHttpUtils.DATADOG_ENTITY_ID;
import static datadog.communication.http.OkHttpUtils.DATADOG_EXTERNAL_ENV;
import static datadog.communication.http.OkHttpUtils.addOriginHeaders;
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
  void addsExternalEnvHeaderWithoutContainerOrEntityId() {
    // the external env is the origin detection fallback when the container is not identifiable
    Request request = prepareRequest(URL, emptyMap(), null, null, EXTERNAL_ENV).get().build();

    assertEquals(EXTERNAL_ENV, request.header(DATADOG_EXTERNAL_ENV));
    assertNull(request.header(DATADOG_CONTAINER_ID));
    assertNull(request.header(DATADOG_ENTITY_ID));
  }

  @Test
  void omitsExternalEnvHeaderWhenUnset() {
    Request request = prepareRequest(URL, emptyMap(), null, null, null).get().build();

    assertNull(request.header(DATADOG_EXTERNAL_ENV));
  }

  @Test
  void addOriginHeadersAddsAllHeadersWhenSet() {
    Request request =
        addOriginHeaders(new Request.Builder().url(URL), "containerId", "entityId", EXTERNAL_ENV)
            .build();

    assertEquals("containerId", request.header(DATADOG_CONTAINER_ID));
    assertEquals("entityId", request.header(DATADOG_ENTITY_ID));
    assertEquals(EXTERNAL_ENV, request.header(DATADOG_EXTERNAL_ENV));
  }

  @Test
  void addOriginHeadersOmitsUnsetHeaders() {
    Request request = addOriginHeaders(new Request.Builder().url(URL), null, null, null).build();

    assertEquals(0, request.headers().size());
  }
}
