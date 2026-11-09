package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static java.util.Collections.singletonMap;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Unit tests for {@link JettyCommitResponseHelper}.
 *
 * <p>Uses dynamic proxy fakes so the tests compile against every Jetty 12 version. The response IG
 * callbacks are replaced by a {@link JettyDecorator} subclass.
 */
class JettyCommitResponseHelperTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(
          413, BlockingContentType.JSON, singletonMap("X-Extra", "value"), "rid-1");

  private final Map<String, Object> attributes = new HashMap<>();
  private final HttpFields.Mutable headers = HttpFields.build();
  private final List<ByteBuffer> writes = new ArrayList<>();
  private final List<Callback> writeCallbacks = new ArrayList<>();
  private final FakeDecorator decorator = new FakeDecorator();
  private boolean committed;
  private int status;
  private int resets;
  private RuntimeException resetFailure;
  private int effectivelyBlocked;
  private int blockFailures;
  private int originalSucceeded;
  private Throwable originalFailure;

  private final TraceSegment segment =
      proxy(
          TraceSegment.class,
          (name, args) -> {
            if ("effectivelyBlocked".equals(name)) {
              effectivelyBlocked++;
            }
            return null;
          });

  private final Callback original =
      new Callback() {
        @Override
        public void succeeded() {
          originalSucceeded++;
        }

        @Override
        public void failed(Throwable x) {
          originalFailure = x;
        }

        @Override
        public InvocationType getInvocationType() {
          return NON_BLOCKING;
        }
      };

  @Test
  void committedResponseIsIgnored() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    committed = true;

    assertFalse(tryBlock());
    assertEquals(0, decorator.calls);
    assertNull(attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void ignoreAttributeSkipsEvaluation() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    attributes.put(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);

    assertFalse(tryBlock());
    assertEquals(0, decorator.calls);
  }

  @TableTest({
    "Scenario           | Context | Span  | Request Context",
    "no context         | false   | true  | true           ",
    "no span            | true    | false | true           ",
    "no request context | true    | true  | false          "
  })
  void nothingToEvaluate(boolean hasContext, boolean hasSpan, boolean hasReqCtx) {
    if (hasContext) {
      attributes.put(DD_CONTEXT_ATTRIBUTE, hasSpan ? context(hasReqCtx) : emptyContext());
    }

    assertFalse(tryBlock());
    assertEquals(0, decorator.calls);
    assertNull(attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void interimStatusIsIgnored() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    status = 103;

    assertFalse(tryBlock());
    assertEquals(0, decorator.calls);
  }

  @TableTest({
    "Scenario     | Status | Expected Status",
    "unset status | 0      | 200            ",
    "status set   | 404    | 404            "
  })
  void noActionLetsWriteProceed(int status, int expectedStatus) {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    this.status = status;

    assertFalse(tryBlock());
    assertEquals(1, decorator.calls);
    assertEquals(expectedStatus, decorator.status);
    assertEquals(Boolean.TRUE, attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertSame(ExtractAdapter.Response.GETTER, decorator.visitor);
    assertEquals(0, resets);
    assertTrue(writes.isEmpty());
  }

  @Test
  void blockingActionWritesBlockingResponse() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    headers.put("X-App-Header", "app");
    decorator.action = RBA;

    assertTrue(tryBlock());

    assertEquals(1, resets);
    assertEquals(413, status);
    assertNull(headers.get("X-App-Header"), "reset must clear previous headers");
    assertEquals("value", headers.get("X-Extra"));
    byte[] expectedBody = BlockingActionHelper.getTemplate(TemplateType.JSON, "rid-1");
    assertEquals(
        BlockingActionHelper.getContentType(TemplateType.JSON),
        headers.get(HttpHeader.CONTENT_TYPE));
    assertEquals(1, writes.size());
    assertArrayEquals(expectedBody, toBytes(writes.get(0)));
    Callback wrapped = writeCallbacks.get(0);
    assertInstanceOf(JettyCommitResponseHelper.BlockingCallback.class, wrapped);
    assertEquals(NON_BLOCKING, wrapped.getInvocationType());
    assertEquals(0, effectivelyBlocked, "marked only once the write completes");
    assertEquals(0, originalSucceeded);
  }

  @Test
  void wrappedCallbackSuccessMarksBlocked() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    assertTrue(tryBlock());

    writeCallbacks.get(0).succeeded();

    assertEquals(1, effectivelyBlocked);
    assertEquals(1, originalSucceeded);
    assertNull(originalFailure);
    assertEquals(0, blockFailures);
  }

  @Test
  void wrappedCallbackFailureReportsAndFailsOriginal() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    assertTrue(tryBlock());
    IOException failure = new IOException("broken pipe");

    writeCallbacks.get(0).failed(failure);

    assertEquals(0, effectivelyBlocked);
    assertEquals(1, blockFailures);
    assertSame(failure, originalFailure);
    assertEquals(0, originalSucceeded);
  }

  @Test
  void nullOriginalCallbackIsTolerated() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;

    assertTrue(JettyCommitResponseHelper.tryBlockOnCommit(decorator, response(), null));
    writeCallbacks.get(0).succeeded();

    assertEquals(1, effectivelyBlocked);
  }

  @Test
  void resetFailureFailsOpenAndReports() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    resetFailure = new IllegalStateException("committed");

    assertFalse(tryBlock());
    assertTrue(writes.isEmpty());
    assertEquals(1, blockFailures);
  }

  @Test
  void committedDuringEvaluationFailsOpenAndReports() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    decorator.commitOnCall = true;

    assertFalse(tryBlock());
    assertEquals(0, resets);
    assertTrue(writes.isEmpty());
    assertEquals(1, blockFailures);
  }

  private boolean tryBlock() {
    return JettyCommitResponseHelper.tryBlockOnCommit(decorator, response(), original);
  }

  private Response response() {
    Request request =
        proxy(
            Request.class,
            (name, args) -> {
              switch (name) {
                case "getAttribute":
                  return attributes.get((String) args[0]);
                case "setAttribute":
                  return attributes.put((String) args[0], args[1]);
                case "getMethod":
                  return "GET";
                case "getHeaders":
                  return HttpFields.EMPTY;
                default:
                  throw new UnsupportedOperationException(name);
              }
            });
    return proxy(
        Response.class,
        (name, args) -> {
          switch (name) {
            case "isCommitted":
              return committed;
            case "getRequest":
              return request;
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
              writes.add((ByteBuffer) args[1]);
              writeCallbacks.add((Callback) args[2]);
              return null;
            default:
              throw new UnsupportedOperationException(name);
          }
        });
  }

  private Context context(boolean hasReqCtx) {
    AppSecContext appSecCtx =
        proxy(
            AppSecContext.class,
            (name, args) -> {
              if ("reportBlockFailure".equals(name)) {
                blockFailures++;
              }
              return null;
            });
    RequestContext reqCtx =
        proxy(
            RequestContext.class,
            (name, args) -> {
              switch (name) {
                case "getTraceSegment":
                  return segment;
                case "getData":
                  return args[0] == RequestContextSlot.APPSEC ? appSecCtx : null;
                default:
                  return null;
              }
            });
    AgentSpan span =
        proxy(
            AgentSpan.class,
            (name, args) -> "getRequestContext".equals(name) && hasReqCtx ? reqCtx : null);
    return proxy(Context.class, (name, args) -> "get".equals(name) ? span : null);
  }

  private static Context emptyContext() {
    return proxy(Context.class, (name, args) -> null);
  }

  private static byte[] toBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }

  private final class FakeDecorator extends JettyDecorator {
    int calls;
    int status;
    Object visitor;
    Flow.Action action = Flow.Action.Noop.INSTANCE;
    boolean commitOnCall;

    @Override
    public <RESP> Flow<Void> callIGCallbackResponseAndHeaders(
        AgentSpan span,
        RESP carrier,
        int status,
        AgentPropagation.ContextVisitor<RESP> contextVisitor) {
      calls++;
      this.status = status;
      this.visitor = contextVisitor;
      if (commitOnCall) {
        committed = true;
      }
      Flow.Action result = action;
      return new Flow<Void>() {
        @Override
        public Action getAction() {
          return result;
        }

        @Override
        public Void getResult() {
          return null;
        }
      };
    }
  }

  private interface Handler {
    Object handle(String methodName, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            JettyCommitResponseHelperTest.class.getClassLoader(),
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
                  return handler.handle(method.getName(), args);
              }
            });
  }
}
