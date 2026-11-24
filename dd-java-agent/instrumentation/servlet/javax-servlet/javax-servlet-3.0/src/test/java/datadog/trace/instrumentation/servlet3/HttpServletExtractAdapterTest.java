package datadog.trace.instrumentation.servlet3;

import static datadog.trace.instrumentation.servlet3.LegacyResponses.oldResponse;
import static datadog.trace.instrumentation.servlet3.LegacyResponses.oldWrapper;
import static datadog.trace.instrumentation.servlet3.LegacyResponses.partialResponse;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class HttpServletExtractAdapterTest {

  private static final HttpServletExtractAdapter.Response GETTER =
      HttpServletExtractAdapter.Response.GETTER;
  private static final HttpServletExtractAdapter.Response.HeaderAccessLatch HEADER_LATCH =
      HttpServletExtractAdapter.Response.HEADER_LATCH;

  @Test
  void readsHeadersFromSupportedResponse() {
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(supportedResponse(), collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
  }

  @Test
  void skipsResponseClassWithoutHeaderAccessors() throws Exception {
    // sanity check: calling the Servlet 3.0 method on this class really fails
    HttpServletResponse old = oldResponse("OldResponse");
    assertThrows(AbstractMethodError.class, old::getHeaderNames);
    List<String> seen = new ArrayList<>();

    // the first response of the class trips the failure path
    GETTER.forEachKey(old, collectInto(seen));
    // later responses of the same class take the remembered path
    GETTER.forEachKey(old, collectInto(seen));

    assertEquals(emptyList(), seen);
  }

  @Test
  void healthyResponsesStillWorkAfterAFailure() throws Exception {
    // tests can run in any order, so trip the failure path here to cover healthy responses after it
    List<String> failed = new ArrayList<>();
    GETTER.forEachKey(oldResponse("OldResponseTwo"), collectInto(failed));
    assertEquals(emptyList(), failed);
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(supportedResponse(), collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
  }

  @Test
  void callbackFailureDoesNotMarkResponseClassUnsupported() {
    // an AbstractMethodError raised by the callback says nothing about the response class
    assertThrows(
        AbstractMethodError.class,
        () ->
            GETTER.forEachKey(
                supportedResponse(),
                (key, value) -> {
                  throw new AbstractMethodError();
                }));
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(supportedResponse(), collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
  }

  @Test
  void latchesResponseClassWithoutHeaderAccessors() throws Exception {
    warmAccessorCallSites();
    HttpServletResponse old = oldResponse("OldResponseLatched");
    assertFalse(HEADER_LATCH.isLatched(old));

    GETTER.forEachKey(old, collectInto(new ArrayList<>()));

    assertTrue(HEADER_LATCH.isLatched(old));
    assertFalse(HEADER_LATCH.isLatched(supportedResponse()));
  }

  @Test
  void latchesResponseClassMissingOnlyGetHeader() throws Exception {
    warmAccessorCallSites();
    // not producible by javac, but bytecode generators can implement one accessor and not the other
    HttpServletResponse partial = partialResponse("PartialResponse");
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(partial, collectInto(seen));
    GETTER.forEachKey(partial, collectInto(seen));

    assertEquals(emptyList(), seen);
    assertTrue(HEADER_LATCH.isLatched(partial));
  }

  @Test
  void oldWrapperSubclassInheritsHeaderAccessors() throws Exception {
    // a wrapper compiled against Servlet 2.5 inherits the container's delegating accessors, which
    // is why these classes need no unwrapping
    HttpServletResponse oldWrapper = oldWrapper("OldWrapper", supportedResponse());
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(oldWrapper, collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
    assertFalse(HEADER_LATCH.isLatched(oldWrapper));
  }

  /**
   * Calls the accessors on a working class first. JDK 8 then throws the AbstractMethodError without
   * a message, as it does in production, where the call sites have seen healthy responses.
   */
  private static void warmAccessorCallSites() {
    GETTER.forEachKey(supportedResponse(), collectInto(new ArrayList<>()));
  }

  private static HttpServletResponse supportedResponse() {
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            HttpServletResponse.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getHeaderNames":
                  return asList("x-a", "x-b");
                case "getHeader":
                  return "x-a".equals(args[0]) ? "1" : "2";
                default:
                  return null;
              }
            });
  }

  private static AgentPropagation.KeyClassifier collectInto(List<String> seen) {
    return (key, value) -> {
      seen.add(key + "=" + value);
      return true;
    };
  }
}
