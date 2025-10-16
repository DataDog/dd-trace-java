package datadog.trace.common.metrics;

import static org.junit.Assert.*;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Collections;
import org.junit.Test;

public class MetricKeyTest {

  @Test
  public void testMetricKeyWithHttpMethodAndEndpoint() {
    MetricKey key =
        new MetricKey(
            "GET /api/users/?",
            "my-service",
            "http.request",
            "web",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users/?");

    assertEquals(UTF8BytesString.create("GET /api/users/?"), key.getResource());
    assertEquals(UTF8BytesString.create("my-service"), key.getService());
    assertEquals(UTF8BytesString.create("http.request"), key.getOperationName());
    assertEquals(UTF8BytesString.create("web"), key.getType());
    assertEquals(200, key.getHttpStatusCode());
    assertFalse(key.isSynthetics());
    assertTrue(key.isTraceRoot());
    assertEquals(UTF8BytesString.create("server"), key.getSpanKind());
    assertEquals(Collections.emptyList(), key.getPeerTags());
    assertEquals(UTF8BytesString.create("GET"), key.getHttpMethod());
    assertEquals(UTF8BytesString.create("/api/users/?"), key.getHttpEndpoint());
  }

  @Test
  public void testMetricKeyWithNullHttpMethodAndEndpoint() {
    MetricKey key =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            0,
            false,
            false,
            "client",
            Collections.emptyList(),
            null,
            null);

    assertEquals(UTF8BytesString.EMPTY, key.getHttpMethod());
    assertEquals(UTF8BytesString.EMPTY, key.getHttpEndpoint());
  }

  @Test
  public void testMetricKeyEqualsWithHttpMethodAndEndpoint() {
    MetricKey key1 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "POST",
            "/api/orders/?");

    MetricKey key2 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "POST",
            "/api/orders/?");

    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());
  }

  @Test
  public void testMetricKeyNotEqualsWithDifferentHttpMethod() {
    MetricKey key1 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users/?");

    MetricKey key2 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "POST",
            "/api/users/?");

    assertNotEquals(key1, key2);
  }

  @Test
  public void testMetricKeyNotEqualsWithDifferentHttpEndpoint() {
    MetricKey key1 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users/?");

    MetricKey key2 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/orders/?");

    assertNotEquals(key1, key2);
  }

  @Test
  public void testMetricKeyHashCodeIncludesHttpMethodAndEndpoint() {
    MetricKey key1 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users/?");

    MetricKey key2 =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            200,
            false,
            true,
            "server",
            Collections.emptyList(),
            "POST",
            "/api/users/?");

    // Hash codes should be different when HTTP method differs
    assertNotEquals(key1.hashCode(), key2.hashCode());
  }

  @Test
  public void testMetricKeyWithEmptyHttpMethodAndEndpoint() {
    MetricKey key =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "type",
            0,
            false,
            false,
            "client",
            Collections.emptyList(),
            "",
            "");

    assertEquals(UTF8BytesString.create(""), key.getHttpMethod());
    assertEquals(UTF8BytesString.create(""), key.getHttpEndpoint());
  }
}
