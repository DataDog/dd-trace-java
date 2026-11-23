package datadog.trace.instrumentation.springweb6;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that bodies handled by Spring 7 {@code SmartHttpMessageConverter}s (Jackson 3 {@code
 * JacksonJsonHttpMessageConverter}) reach the AppSec gateway callbacks.
 *
 * <p>Spring calls live in {@link Spring7ConverterCalls}, and gateway state in {@link
 * GatewayFixture}, so that this class neither loads Spring classes before the agent is installed
 * nor exposes bootstrap types in its members.
 */
class AppSecSmartHttpMessageConverterTest extends AbstractInstrumentationTest {

  private static final String JSON = "{\"value\":\"example\"}";
  private static final Map<String, Object> EXPECTED = singletonMap("value", "example");

  private final GatewayFixture gateway = new GatewayFixture();

  @BeforeEach
  void setUp() {
    gateway.start();
  }

  @AfterEach
  void cleanUp() {
    gateway.close();
  }

  /** Sanity check: the legacy {@code read(Class, HttpInputMessage)} path is already advised. */
  @Test
  void classBasedReadPublishesRequestBody() throws Exception {
    assertEquals(EXPECTED, Spring7ConverterCalls.classRead(JSON));
    assertEquals(1, gateway.requestBodies.size(), "requestBodyProcessed publications");
    assertEquals(EXPECTED, gateway.requestBodies.get(0));
  }

  @Test
  void smartReadPublishesRequestBody() throws Exception {
    assertEquals(EXPECTED, Spring7ConverterCalls.smartRead(JSON));
    assertEquals(1, gateway.requestBodies.size(), "requestBodyProcessed publications");
    assertEquals(EXPECTED, gateway.requestBodies.get(0));
  }

  /** Raw strings are not parsed bodies: the Smart read must keep skipping them. */
  @Test
  void smartReadOfStringDoesNotPublish() throws Exception {
    assertEquals("example", Spring7ConverterCalls.smartReadString("\"example\""));
    assertEquals(0, gateway.requestBodies.size(), "requestBodyProcessed publications");
  }

  @Test
  void mockMvcRequestBodyMapIsPublished() throws Exception {
    assertEquals(EXPECTED, Spring7ConverterCalls.mockMvcPost(JSON));
    assertEquals(1, gateway.requestBodies.size(), "requestBodyProcessed publications");
    assertEquals(EXPECTED, gateway.requestBodies.get(0));
  }

  @Test
  void smartWritePublishesResponseBody() throws Exception {
    assertEquals(JSON, Spring7ConverterCalls.smartWrite(EXPECTED));
    assertEquals(1, gateway.responseBodies.size(), "responseBody publications");
    assertEquals(EXPECTED, gateway.responseBodies.get(0));
  }

  @Test
  void smartReadBlocksWhenCommitSucceeds() {
    gateway.blockWithSuccessfulCommit();

    assertThrows(BlockingException.class, () -> Spring7ConverterCalls.smartRead(JSON));
    assertEquals(1, gateway.requestBodies.size(), "requestBodyProcessed publications");
    assertEquals(EXPECTED, gateway.requestBodies.get(0));
  }

  @Test
  void smartWriteBlocksWhenCommitSucceeds() {
    gateway.blockWithSuccessfulCommit();

    assertThrows(BlockingException.class, () -> Spring7ConverterCalls.smartWrite(EXPECTED));
    assertEquals(1, gateway.responseBodies.size(), "responseBody publications");
    assertEquals(EXPECTED, gateway.responseBodies.get(0));
  }

  /** Active AppSec span plus recording callbacks for the request and response body events. */
  private static class GatewayFixture {
    final List<Object> requestBodies = new ArrayList<>();
    final List<Object> responseBodies = new ArrayList<>();
    private final SubscriptionService ss =
        AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
    private Flow<Void> callbackFlow = Flow.ResultFlow.empty();
    private AgentSpan span;
    private ContextScope scope;

    void start() {
      TagContext ctx = new TagContext().withRequestContextDataAppSec(new Object());
      span = startSpan("test", "test-span", ctx);
      scope = activateSpan(span);
      ss.registerCallback(EVENTS.requestBodyProcessed(), this::onRequestBody);
      ss.registerCallback(EVENTS.responseBody(), this::onResponseBody);
    }

    /** Makes callbacks request a block, and the request context commit it successfully. */
    void blockWithSuccessfulCommit() {
      span.getRequestContext().setBlockResponseFunction(new SuccessfulBlockResponseFunction());
      callbackFlow = new BlockingFlow();
    }

    void close() {
      try {
        ss.reset();
      } finally {
        if (scope != null) {
          scope.close();
        }
        if (span != null) {
          span.finish();
        }
      }
    }

    private Flow<Void> onRequestBody(RequestContext reqCtx, Object body) {
      requestBodies.add(body);
      return callbackFlow;
    }

    private Flow<Void> onResponseBody(RequestContext reqCtx, Object body) {
      responseBodies.add(body);
      return callbackFlow;
    }
  }

  /** Flow requesting a 403 block. */
  private static class BlockingFlow extends Flow.ResultFlow<Void> {
    BlockingFlow() {
      super(null);
    }

    @Override
    public Flow.Action getAction() {
      return new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);
    }
  }

  /** Block response function that always reports a committed blocking response. */
  private static class SuccessfulBlockResponseFunction implements BlockResponseFunction {
    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      return true;
    }
  }
}
