package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import org.junit.jupiter.api.Test;

class CardinalityHandlerTest {

  @Test
  void propertyReturnsSameInstanceForRepeatedValueUntilLimit() {
    PropertyCardinalityHandler h = new PropertyCardinalityHandler(3);
    UTF8BytesString a1 = h.register("a");
    UTF8BytesString a2 = h.register("a");
    assertSame(a1, a2);
    assertEquals("a", a1.toString());
  }

  @Test
  void propertyOverLimitReturnsBlockedSentinel() {
    PropertyCardinalityHandler h = new PropertyCardinalityHandler(2);
    UTF8BytesString a = h.register("a");
    UTF8BytesString b = h.register("b");
    UTF8BytesString blocked1 = h.register("c");
    UTF8BytesString blocked2 = h.register("d");

    assertEquals("blocked_by_tracer", blocked1.toString());
    assertSame(blocked1, blocked2); // same sentinel for all overflow values
    assertNotSame(blocked1, a);
    assertNotSame(blocked1, b);
  }

  @Test
  void propertyResetRefreshesBudget() {
    PropertyCardinalityHandler h = new PropertyCardinalityHandler(2);
    h.register("a");
    h.register("b");
    UTF8BytesString blocked = h.register("c");
    assertEquals("blocked_by_tracer", blocked.toString());

    h.reset();

    // After reset, three distinct values fit again, but the previous instances aren't reused.
    UTF8BytesString afterReset = h.register("a");
    assertEquals("a", afterReset.toString());
    UTF8BytesString c = h.register("c");
    assertEquals("c", c.toString());
    UTF8BytesString blockedAgain = h.register("d");
    UTF8BytesString blockedYetAgain = h.register("e");
    assertEquals("blocked_by_tracer", blockedAgain.toString());
    assertSame(blockedAgain, blockedYetAgain);
  }

  @Test
  void tagPrefixesValuesAndReusesUnderLimit() {
    TagCardinalityHandler h = new TagCardinalityHandler("peer.hostname", 4);
    UTF8BytesString first = h.register("host-a");
    UTF8BytesString second = h.register("host-a");
    UTF8BytesString other = h.register("host-b");

    assertSame(first, second);
    assertNotSame(first, other);
    assertEquals("peer.hostname:host-a", first.toString());
    assertEquals("peer.hostname:host-b", other.toString());
  }

  @Test
  void tagOverLimitReturnsTaggedSentinel() {
    TagCardinalityHandler h = new TagCardinalityHandler("peer.service", 1);
    h.register("svc-1");
    UTF8BytesString blocked = h.register("svc-2");
    assertEquals("peer.service:blocked_by_tracer", blocked.toString());
  }

  @Test
  void tagResetRefreshesBudgetAndSentinelStaysStable() {
    TagCardinalityHandler h = new TagCardinalityHandler("x", 1);
    h.register("v1");
    UTF8BytesString blockedBefore = h.register("v2");
    h.reset();
    h.register("v1");
    UTF8BytesString blockedAfter = h.register("v2");
    // Both are the same sentinel instance (cacheBlocked is not cleared on reset).
    assertSame(blockedBefore, blockedAfter);
  }

  @Test
  void propertyRegisterOfNullReturnsEmpty() {
    PropertyCardinalityHandler h = new PropertyCardinalityHandler(4);
    // Null input short-circuits to UTF8BytesString.EMPTY -- the universal "absent" sentinel that
    // AggregateEntry's optional UTF8 fields use in place of null.
    assertSame(UTF8BytesString.EMPTY, h.register(null));
  }

  @Test
  void propertyRegisterOfNullDoesNotConsumeBudget() {
    PropertyCardinalityHandler h = new PropertyCardinalityHandler(2);
    h.register(null);
    h.register(null);
    h.register(null);
    // Three null registrations didn't consume the budget; two real values still fit.
    assertEquals("a", h.register("a").toString());
    assertEquals("b", h.register("b").toString());
    // Third real value spills to the blocked sentinel (limit = 2).
    assertEquals("blocked_by_tracer", h.register("c").toString());
  }

  @Test
  void tagRegisterOfNullReturnsEmpty() {
    TagCardinalityHandler h = new TagCardinalityHandler("peer.hostname", 4);
    // Null returns EMPTY (no "tag:" prefix applied -- the sentinel is the same EMPTY singleton
    // every handler returns for null input).
    assertSame(UTF8BytesString.EMPTY, h.register(null));
  }
}
