package datadog.communication.http;

import static datadog.communication.http.OkHttpUtils.DATADOG_CONTAINER_ID;
import static datadog.communication.http.OkHttpUtils.DATADOG_ENTITY_ID;
import static datadog.communication.http.OkHttpUtils.DATADOG_EXTERNAL_ENV;
import static datadog.communication.http.OkHttpUtils.addOriginHeaders;
import static datadog.communication.http.OkHttpUtils.isAgentUrl;
import static datadog.communication.http.OkHttpUtils.prepareRequest;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.common.container.ContainerInfo;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

class OkHttpUtilsPrepareRequestTest extends DDJavaSpecification {

  private static final HttpUrl URL = HttpUrl.get("http://localhost:8126/v0.4/traces");
  private static final String EXTERNAL_ENV =
      "it-false,cn-nginx-webserver,pu-75a2b6d5-3949-4afb-ad0d-92ff0674e759";

  @Test
  void addsOriginHeadersWhenSet() {
    ContainerInfo containerInfo = containerInfo("containerId", "entityId", EXTERNAL_ENV);

    Request request = prepareRequest(URL, emptyMap(), containerInfo, true).get().build();

    assertEquals("containerId", request.header(DATADOG_CONTAINER_ID));
    assertEquals("entityId", request.header(DATADOG_ENTITY_ID));
    assertEquals(EXTERNAL_ENV, request.header(DATADOG_EXTERNAL_ENV));
  }

  @Test
  void addsExternalEnvHeaderWithoutContainerOrEntityId() {
    // the external env is the origin detection fallback when the container is not identifiable
    ContainerInfo containerInfo = containerInfo(null, null, EXTERNAL_ENV);

    Request request = prepareRequest(URL, emptyMap(), containerInfo, true).get().build();

    assertEquals(EXTERNAL_ENV, request.header(DATADOG_EXTERNAL_ENV));
    assertNull(request.header(DATADOG_CONTAINER_ID));
    assertNull(request.header(DATADOG_ENTITY_ID));
  }

  @Test
  void omitsExternalEnvHeaderWhenUnset() {
    ContainerInfo containerInfo = containerInfo(null, null, null);

    Request request = prepareRequest(URL, emptyMap(), containerInfo, true).get().build();

    assertNull(request.header(DATADOG_EXTERNAL_ENV));
  }

  @Test
  void omitsOriginHeadersWhenNotSentToAgent() {
    ContainerInfo containerInfo = containerInfo("containerId", "entityId", EXTERNAL_ENV);

    Request request = prepareRequest(URL, emptyMap(), containerInfo, false).get().build();

    assertNull(request.header(DATADOG_CONTAINER_ID));
    assertNull(request.header(DATADOG_ENTITY_ID));
    assertNull(request.header(DATADOG_EXTERNAL_ENV));
  }

  @Test
  void addOriginHeadersOmitsUnsetHeaders() {
    ContainerInfo containerInfo = containerInfo(null, null, null);

    Request request = addOriginHeaders(new Request.Builder().url(URL), containerInfo).build();

    assertEquals(0, request.headers().size());
  }

  @Test
  @WithConfig(key = "trace.agent.url", value = "http://dd-agent:8126")
  void isAgentUrlMatchesConfiguredAgent() {
    Config config = Config.get();

    assertTrue(isAgentUrl(HttpUrl.get("http://dd-agent:8126/v0.4/traces"), config));
    assertTrue(isAgentUrl(HttpUrl.get("http://DD-AGENT:8126/debugger/v1/input"), config));
    assertFalse(isAgentUrl(HttpUrl.get("http://dd-agent:8127/v0.4/traces"), config));
    assertFalse(isAgentUrl(HttpUrl.get("https://dd-agent:8126/v0.4/traces"), config));
    assertFalse(isAgentUrl(HttpUrl.get("https://http-intake.logs.datadoghq.com/"), config));
  }

  @Test
  @WithConfig(key = "trace.agent.url", value = "unix:///var/run/datadog/apm.socket")
  void isAgentUrlMatchesUnixDomainSocketPlaceholder() {
    Config config = Config.get();

    // requests tunnelled over the socket use the default agent host and port as a placeholder
    assertTrue(isAgentUrl(HttpUrl.get("http://localhost:8126/v0.4/traces"), config));
    assertFalse(isAgentUrl(HttpUrl.get("https://http-intake.logs.datadoghq.com/"), config));
  }

  // inherited public ContainerInfo fields would shadow parameters with the same names
  private static ContainerInfo containerInfo(
      String detectedContainerId, String detectedEntityId, String detectedExternalEnv) {
    return new ContainerInfo() {
      @Override
      public String getContainerId() {
        return detectedContainerId;
      }

      @Override
      public String getEntityId() {
        return detectedEntityId;
      }

      @Override
      public String getExternalEnv() {
        return detectedExternalEnv;
      }
    };
  }
}
