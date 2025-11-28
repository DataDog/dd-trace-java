package datadog.trace.api.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class FlowTest {

  @Test
  public void testRequestBlockingActionWithSecurityResponseId() {
    String securityResponseId = "12345678-1234-4234-8234-123456789abc";
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(
            403, BlockingContentType.JSON, Collections.emptyMap(), securityResponseId);

    assertTrue(rba.isBlocking());
    assertEquals(403, rba.getStatusCode());
    assertEquals(BlockingContentType.JSON, rba.getBlockingContentType());
    assertEquals(securityResponseId, rba.getSecurityResponseId());
    assertTrue(rba.getExtraHeaders().isEmpty());
  }

  @Test
  public void testRequestBlockingActionWithNullSecurityResponseId() {
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(
            403, BlockingContentType.HTML, Collections.emptyMap(), null);

    assertTrue(rba.isBlocking());
    assertEquals(403, rba.getStatusCode());
    assertEquals(BlockingContentType.HTML, rba.getBlockingContentType());
    assertNull(rba.getSecurityResponseId());
  }

  @Test
  public void testRequestBlockingActionWithoutSecurityResponseId() {
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

    assertTrue(rba.isBlocking());
    assertEquals(403, rba.getStatusCode());
    assertEquals(BlockingContentType.AUTO, rba.getBlockingContentType());
    assertNull(rba.getSecurityResponseId());
  }

  @Test
  public void testRequestBlockingActionWithExtraHeadersAndSecurityResponseId() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Custom-Header", "custom-value");
    headers.put("Content-Language", "en-US");
    String securityResponseId = "87654321-4321-4321-9234-987654321abc";

    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(
            429, BlockingContentType.JSON, headers, securityResponseId);

    assertTrue(rba.isBlocking());
    assertEquals(429, rba.getStatusCode());
    assertEquals(BlockingContentType.JSON, rba.getBlockingContentType());
    assertEquals(securityResponseId, rba.getSecurityResponseId());
    assertEquals(2, rba.getExtraHeaders().size());
    assertEquals("custom-value", rba.getExtraHeaders().get("X-Custom-Header"));
    assertEquals("en-US", rba.getExtraHeaders().get("Content-Language"));
  }

  @Test
  public void testForRedirectWithSecurityResponseId() {
    String securityResponseId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    Flow.Action.RequestBlockingAction rba =
        Flow.Action.RequestBlockingAction.forRedirect(
            303, "https://example.com/blocked", securityResponseId);

    assertTrue(rba.isBlocking());
    assertEquals(303, rba.getStatusCode());
    assertEquals(BlockingContentType.NONE, rba.getBlockingContentType());
    assertEquals(securityResponseId, rba.getSecurityResponseId());
    assertNotNull(rba.getExtraHeaders());
    assertEquals(1, rba.getExtraHeaders().size());
    assertEquals("https://example.com/blocked", rba.getExtraHeaders().get("Location"));
  }

  @Test
  public void testForRedirectWithoutSecurityResponseId() {
    Flow.Action.RequestBlockingAction rba =
        Flow.Action.RequestBlockingAction.forRedirect(302, "https://example.com/redirect");

    assertTrue(rba.isBlocking());
    assertEquals(302, rba.getStatusCode());
    assertEquals(BlockingContentType.NONE, rba.getBlockingContentType());
    assertNull(rba.getSecurityResponseId());
    assertEquals("https://example.com/redirect", rba.getExtraHeaders().get("Location"));
  }

  @Test
  public void testForRedirectWithNullSecurityResponseId() {
    Flow.Action.RequestBlockingAction rba =
        Flow.Action.RequestBlockingAction.forRedirect(301, "https://example.com/moved", null);

    assertTrue(rba.isBlocking());
    assertEquals(301, rba.getStatusCode());
    assertEquals(BlockingContentType.NONE, rba.getBlockingContentType());
    assertNull(rba.getSecurityResponseId());
    assertEquals("https://example.com/moved", rba.getExtraHeaders().get("Location"));
  }

  @Test
  public void testRequestBlockingActionWithEmptySecurityResponseId() {
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(
            403, BlockingContentType.JSON, Collections.emptyMap(), "");

    assertTrue(rba.isBlocking());
    assertEquals(403, rba.getStatusCode());
    assertEquals("", rba.getSecurityResponseId());
  }

  @Test
  public void testRequestBlockingActionWithLongSecurityResponseId() {
    // Test with an unusually long securityResponseId to ensure robustness
    String longSecurityResponseId =
        "12345678-1234-4234-8234-123456789abc-extra-long-suffix-that-might-not-be-standard";
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(
            403, BlockingContentType.HTML, Collections.emptyMap(), longSecurityResponseId);

    assertTrue(rba.isBlocking());
    assertEquals(longSecurityResponseId, rba.getSecurityResponseId());
  }

  @Test
  public void testNoopActionIsNotBlocking() {
    assertFalse(Flow.Action.Noop.INSTANCE.isBlocking());
  }

  @Test
  public void testResultFlowWithAction() {
    Flow.ResultFlow<String> flow = new Flow.ResultFlow<>("test-result");

    assertEquals("test-result", flow.getResult());
    assertNotNull(flow.getAction());
    assertFalse(flow.getAction().isBlocking());
    assertEquals(Flow.Action.Noop.INSTANCE, flow.getAction());
  }

  @Test
  public void testEmptyResultFlow() {
    Flow.ResultFlow<String> flow = Flow.ResultFlow.empty();

    assertNull(flow.getResult());
    assertNotNull(flow.getAction());
    assertFalse(flow.getAction().isBlocking());
  }
}
