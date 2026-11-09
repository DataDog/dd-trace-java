package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Unit tests for {@link JettyBlockResponseHelper} and {@link JettyBlockResponseFunction}.
 *
 * <p>Uses dynamic proxy fakes so the tests compile against every Jetty 12 version (the core
 * interfaces gain abstract methods across minor versions).
 */
class JettyBlockResponseHelperTest {

  @TableTest({
    "Scenario            | Content Type | Accept                             | Expected Type",
    "json                | JSON         | text/html                          | JSON         ",
    "html                | HTML         | application/json                   | HTML         ",
    "auto without accept | AUTO         |                                    | JSON         ",
    "auto prefers html   | AUTO         | text/html                          | HTML         ",
    "auto prefers json   | AUTO         | 'text/html;q=0.5,application/json' | JSON         "
  })
  void writesTemplateWithContentHeaders(
      BlockingContentType contentType, String accept, TemplateType expectedType) {
    FakeRequest request = new FakeRequest("GET", accept);
    FakeResponse response = new FakeResponse();
    response.headers.put("X-App-Header", "app");
    FakeSegment segment = new FakeSegment();

    boolean committed =
        JettyBlockResponseHelper.commitBlockingResponse(
            segment.proxy,
            request.proxy,
            response.proxy,
            418,
            contentType,
            singletonMap("X-Extra", "value"),
            "rid-1");

    assertTrue(committed);
    byte[] expectedBody = BlockingActionHelper.getTemplate(expectedType, "rid-1");
    assertEquals(Boolean.TRUE, request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(1, response.resets);
    assertEquals(418, response.status);
    assertNull(response.headers.get("X-App-Header"), "reset must clear previous headers");
    assertEquals("value", response.headers.get("X-Extra"));
    assertEquals(
        BlockingActionHelper.getContentType(expectedType),
        response.headers.get(HttpHeader.CONTENT_TYPE));
    assertEquals(
        String.valueOf(expectedBody.length), response.headers.get(HttpHeader.CONTENT_LENGTH));
    assertEquals(1, response.writes.size());
    assertTrue(response.lastFlags.get(0));
    assertArrayEquals(expectedBody, response.writes.get(0));
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void invalidStatusFallsBackToDefault() {
    FakeResponse response = new FakeResponse();
    assertTrue(commit(new FakeRequest("GET", null), response, new FakeSegment(), 42));
    assertEquals(403, response.status);
  }

  @Test
  void noneKeepsOnlyStatusAndExtraHeaders() {
    FakeResponse response = new FakeResponse();
    FakeSegment segment = new FakeSegment();

    boolean committed =
        JettyBlockResponseHelper.commitBlockingResponse(
            segment.proxy,
            new FakeRequest("GET", "text/html").proxy,
            response.proxy,
            301,
            BlockingContentType.NONE,
            singletonMap("Location", "https://example.com/"),
            null);

    assertTrue(committed);
    assertEquals(301, response.status);
    assertEquals("https://example.com/", response.headers.get("Location"));
    assertEquals(1, response.headers.size());
    assertEquals(1, response.writes.size());
    assertEquals(0, response.writes.get(0).length);
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void headRequestHasNoBody() {
    FakeResponse response = new FakeResponse();
    assertTrue(commit(new FakeRequest("HEAD", "application/json"), response, new FakeSegment()));
    assertEquals(403, response.status);
    assertNull(response.headers.get(HttpHeader.CONTENT_TYPE));
    assertNull(response.headers.get(HttpHeader.CONTENT_LENGTH));
    assertEquals(0, response.writes.get(0).length);
  }

  @Test
  void prepareReturnsNullWhenCommitted() {
    FakeRequest request = new FakeRequest("GET", null);
    FakeResponse response = new FakeResponse();
    response.committed = true;

    assertNull(
        JettyBlockResponseHelper.prepareBlockResponse(
            request.proxy, response.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(0, response.resets);
  }

  @Test
  void prepareDoesNotWrite() {
    FakeResponse response = new FakeResponse();
    ByteBuffer body =
        JettyBlockResponseHelper.prepareBlockResponse(
            new FakeRequest("GET", null).proxy,
            response.proxy,
            403,
            BlockingContentType.JSON,
            emptyMap(),
            null);

    assertArrayEquals(BlockingActionHelper.getTemplate(TemplateType.JSON, null), toBytes(body));
    assertTrue(response.writes.isEmpty());
  }

  @Test
  void committedResponseIsNotBlocked() {
    FakeResponse response = new FakeResponse();
    response.committed = true;
    FakeSegment segment = new FakeSegment();

    assertFalse(commit(new FakeRequest("GET", null), response, segment));
    assertEquals(0, response.resets);
    assertTrue(response.writes.isEmpty());
    assertEquals(0, segment.effectivelyBlockedCalls);
  }

  @Test
  void resetFailureIsNotBlocked() {
    FakeResponse response = new FakeResponse();
    response.resetFailure = new IllegalStateException("committed");
    FakeSegment segment = new FakeSegment();

    assertFalse(commit(new FakeRequest("GET", null), response, segment));
    assertTrue(response.writes.isEmpty());
    assertEquals(0, segment.effectivelyBlockedCalls);
  }

  @Test
  void writeFailureIsNotBlocked() {
    FakeResponse response = new FakeResponse();
    response.writeFailure = new IOException("broken pipe");
    FakeSegment segment = new FakeSegment();

    assertFalse(commit(new FakeRequest("GET", null), response, segment));
    assertEquals(1, response.writes.size());
    assertEquals(0, segment.effectivelyBlockedCalls);
  }

  @Test
  void writeThrowingIsNotBlocked() {
    FakeResponse response = new FakeResponse();
    response.writeThrows = new WritePendingException();
    FakeSegment segment = new FakeSegment();

    assertFalse(commit(new FakeRequest("GET", null), response, segment));
    assertEquals(0, segment.effectivelyBlockedCalls);
  }

  @Test
  void interruptedWaitIsNotBlockedAndKeepsInterruptFlag() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();

    Thread.currentThread().interrupt();
    try {
      assertFalse(commit(new FakeRequest("GET", null), response, segment));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(0, segment.effectivelyBlockedCalls);
  }

  @Test
  void effectivelyBlockedFailureKeepsSuccess() {
    FakeSegment segment = new FakeSegment();
    segment.failure = new IllegalStateException("segment finished");

    assertTrue(commit(new FakeRequest("GET", null), new FakeResponse(), segment));
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void functionShortCircuitsAfterSuccessfulCommit() {
    FakeResponse response = new FakeResponse();
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf =
        new JettyBlockResponseFunction(new FakeRequest("GET", null).proxy, response.proxy);

    assertTrue(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    response.committed = true;
    assertTrue(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));

    assertEquals(1, response.writes.size());
    assertEquals(1, response.resets);
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void functionRetriesAfterFailedCommit() {
    FakeResponse response = new FakeResponse();
    response.writeFailure = new IOException("broken pipe");
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf =
        new JettyBlockResponseFunction(new FakeRequest("GET", null).proxy, response.proxy);

    assertFalse(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    response.writeFailure = null;
    assertTrue(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));

    assertEquals(2, response.writes.size());
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  private static boolean commit(FakeRequest request, FakeResponse response, FakeSegment segment) {
    return commit(request, response, segment, 403);
  }

  private static boolean commit(
      FakeRequest request, FakeResponse response, FakeSegment segment, int status) {
    return JettyBlockResponseHelper.commitBlockingResponse(
        segment.proxy,
        request.proxy,
        response.proxy,
        status,
        BlockingContentType.AUTO,
        emptyMap(),
        null);
  }

  private static byte[] toBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T)
        Proxy.newProxyInstance(
            JettyBlockResponseHelperTest.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "toString":
                  return type.getSimpleName() + "Fake";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                default:
                  return handler.invoke(proxy, method, args);
              }
            });
  }

  private static final class FakeRequest {
    final String method;
    final HttpFields headers;
    final Map<String, Object> attributes = new HashMap<>();
    final Request proxy;

    FakeRequest(String method, String accept) {
      this.method = method;
      HttpFields.Mutable fields = HttpFields.build();
      if (accept != null) {
        fields.put(HttpHeader.ACCEPT, accept);
      }
      this.headers = fields.asImmutable();
      this.proxy =
          proxy(
              Request.class,
              (p, m, args) -> {
                switch (m.getName()) {
                  case "getMethod":
                    return this.method;
                  case "getHeaders":
                    return headers;
                  case "setAttribute":
                    return attributes.put((String) args[0], args[1]);
                  case "getAttribute":
                    return attributes.get((String) args[0]);
                  default:
                    throw new UnsupportedOperationException(m.getName());
                }
              });
    }
  }

  private static final class FakeResponse {
    final HttpFields.Mutable headers = HttpFields.build();
    final List<byte[]> writes = new ArrayList<>();
    final List<Boolean> lastFlags = new ArrayList<>();
    final Response proxy;
    int status;
    int resets;
    boolean committed;
    boolean completeWrites = true;
    RuntimeException resetFailure;
    RuntimeException writeThrows;
    Throwable writeFailure;

    FakeResponse() {
      this.proxy =
          proxy(
              Response.class,
              (p, m, args) -> {
                switch (m.getName()) {
                  case "isCommitted":
                    return committed;
                  case "getStatus":
                    return status;
                  case "setStatus":
                    status = (Integer) args[0];
                    return null;
                  case "getHeaders":
                    return headers;
                  case "reset":
                    if (resetFailure != null) {
                      throw resetFailure;
                    }
                    resets++;
                    status = 0;
                    headers.clear();
                    return null;
                  case "write":
                    if (writeThrows != null) {
                      throw writeThrows;
                    }
                    lastFlags.add((Boolean) args[0]);
                    writes.add(toBytes((ByteBuffer) args[1]));
                    Callback callback = (Callback) args[2];
                    if (!completeWrites) {
                      return null;
                    }
                    if (writeFailure != null) {
                      callback.failed(writeFailure);
                    } else {
                      committed = true;
                      callback.succeeded();
                    }
                    return null;
                  default:
                    throw new UnsupportedOperationException(m.getName());
                }
              });
    }
  }

  private static final class FakeSegment {
    final TraceSegment proxy;
    int effectivelyBlockedCalls;
    RuntimeException failure;

    FakeSegment() {
      this.proxy =
          proxy(
              TraceSegment.class,
              (p, m, args) -> {
                if ("effectivelyBlocked".equals(m.getName())) {
                  effectivelyBlockedCalls++;
                  if (failure != null) {
                    throw failure;
                  }
                  return null;
                }
                throw new UnsupportedOperationException(m.getName());
              });
    }
  }
}
