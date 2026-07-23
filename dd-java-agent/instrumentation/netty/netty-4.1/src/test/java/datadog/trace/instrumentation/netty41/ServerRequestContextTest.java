package datadog.trace.instrumentation.netty41;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.util.DefaultAttributeMap;
import org.junit.jupiter.api.Test;

class ServerRequestContextTest {
  private static final int PIPELINING_LIMIT = 1000;

  @Test
  void disablesTrackingWhenPipeliningLimitIsExceeded() {
    DefaultAttributeMap attributes = new DefaultAttributeMap();

    for (int i = 0; i < PIPELINING_LIMIT; i++) {
      assertNotNull(ServerRequestContext.add(attributes, Context.root(), null));
    }

    assertFalse(ServerRequestContext.canTrackRequest(attributes));
    assertNull(ServerRequestContext.nextResponse(attributes));
    assertNull(attributes.attr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(ServerRequestContext.add(attributes, Context.root(), null));
  }

  @Test
  void capturesAcceptHeaderValue() {
    DefaultAttributeMap attributes = new DefaultAttributeMap();
    DefaultHttpHeaders headers = new DefaultHttpHeaders();
    headers.set("accept", "text/html");

    ServerRequestContext serverContext =
        ServerRequestContext.add(attributes, Context.root(), headers.get("accept"));
    headers.set("accept", "application/json");

    assertEquals("text/html", serverContext.acceptHeader());
  }

  @Test
  void tracksBlockedResponseUntilChannelClose() {
    DefaultAttributeMap attributes = new DefaultAttributeMap();
    ServerRequestContext serverContext = ServerRequestContext.add(attributes, Context.root(), null);

    ServerRequestContext.markResponseBlocked(attributes);
    ServerRequestContext.remove(attributes, serverContext);

    assertTrue(ServerRequestContext.isResponseBlocked(attributes));

    ServerRequestContext.closeAll(attributes);

    assertFalse(ServerRequestContext.isResponseBlocked(attributes));
  }
}
