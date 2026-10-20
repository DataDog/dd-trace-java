package datadog.trace.api.http;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class StoredBodyFactoriesTest extends DDJavaSpecification {
  private AgentTracer.TracerAPI originalTracer;

  private AgentTracer.TracerAPI tracerAPI;
  private AgentSpan agentSpan;

  private RequestContext requestContext;
  private CallbackProvider callbackProvider;

  @BeforeEach
  void setup() {
    tracerAPI = mock(AgentTracer.TracerAPI.class);
    requestContext = mock(RequestContext.class);
    callbackProvider = mock(CallbackProvider.class);
    originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerAPI);
    when(tracerAPI.activeSpan()).thenAnswer(invocation -> agentSpan);
    when(tracerAPI.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
  }

  @AfterEach
  void cleanup() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void noActiveSpan() {
    assertNull(StoredBodyFactories.maybeCreateForByte(null, null));
    assertNull(StoredBodyFactories.maybeCreateForChar(null));
    assertSame(
        Flow.ResultFlow.empty(), StoredBodyFactories.maybeDeliverBodyInOneGo("", requestContext));
  }

  @Test
  void noActiveContext() {
    agentSpan = mock(AgentSpan.class);

    StoredByteBody storedByteBody = StoredBodyFactories.maybeCreateForByte(null, null);
    StoredCharBody storedCharBody = StoredBodyFactories.maybeCreateForChar(null);
    Flow<Void> flow = StoredBodyFactories.maybeDeliverBodyInOneGo("", requestContext);

    verify(agentSpan, times(2)).getRequestContext();
    assertNull(storedByteBody);
    assertNull(storedCharBody);
    assertSame(Flow.ResultFlow.empty(), flow);
  }

  @Test
  void noIgCallbacks() {
    assertNull(StoredBodyFactories.maybeCreateForByte(null, null));
    assertNull(StoredBodyFactories.maybeCreateForChar(null));
    assertSame(
        Flow.ResultFlow.empty(), StoredBodyFactories.maybeDeliverBodyInOneGo("", requestContext));
  }

  @Test
  void everythingNeededProvided() {
    agentSpan = mock(AgentSpan.class);
    BiFunction<RequestContext, StoredBodySupplier, Void> mockRequestBodyStart =
        mock(BiFunction.class);
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> mockRequestBodyDone =
        mock(BiFunction.class);
    Flow<Void> mockFlow = mock(Flow.class);
    givenRequestContextAndCallbacks();

    assertNotNull(StoredBodyFactories.maybeCreateForByte(null, null));
    assertNotNull(StoredBodyFactories.maybeCreateForChar(null));

    verifyRequestContextAndCallbacksRequestedTwice();

    clearInvocations(callbackProvider);
    when(callbackProvider.getCallback(EVENTS.requestBodyStart())).thenReturn(mockRequestBodyStart);
    when(callbackProvider.getCallback(EVENTS.requestBodyDone())).thenReturn(mockRequestBodyDone);
    when(mockRequestBodyDone.apply(eq(requestContext), any(StoredBodySupplier.class)))
        .thenReturn(mockFlow);

    Flow<Void> flow =
        StoredBodyFactories.maybeDeliverBodyInOneGo(
            (Supplier<CharSequence>) () -> "body", requestContext);

    verify(callbackProvider).getCallback(EVENTS.requestBodyStart());
    verify(callbackProvider).getCallback(EVENTS.requestBodyDone());
    ArgumentCaptor<StoredBodySupplier> bodySupplier1 =
        ArgumentCaptor.forClass(StoredBodySupplier.class);
    ArgumentCaptor<StoredBodySupplier> bodySupplier2 =
        ArgumentCaptor.forClass(StoredBodySupplier.class);
    verify(mockRequestBodyStart).apply(eq(requestContext), bodySupplier1.capture());
    verify(mockRequestBodyDone).apply(eq(requestContext), bodySupplier2.capture());
    assertSame(bodySupplier2.getValue(), bodySupplier1.getValue());
    assertEquals("body", bodySupplier2.getValue().get());
    assertSame(mockFlow, flow);
  }

  @Test
  void everythingNeededProvidedDeliveryInOneGoStringVariant() {
    agentSpan = mock(AgentSpan.class);
    BiFunction<RequestContext, StoredBodySupplier, Void> mockRequestBodyStart =
        mock(BiFunction.class);
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> mockRequestBodyDone =
        mock(BiFunction.class);
    Flow<Void> mockFlow = mock(Flow.class);
    when(callbackProvider.getCallback(EVENTS.requestBodyStart())).thenReturn(mockRequestBodyStart);
    when(callbackProvider.getCallback(EVENTS.requestBodyDone())).thenReturn(mockRequestBodyDone);
    when(mockRequestBodyDone.apply(eq(requestContext), any(StoredBodySupplier.class)))
        .thenReturn(mockFlow);

    Flow<Void> flow = StoredBodyFactories.maybeDeliverBodyInOneGo("body", requestContext);

    verify(callbackProvider).getCallback(EVENTS.requestBodyStart());
    verify(callbackProvider).getCallback(EVENTS.requestBodyDone());
    ArgumentCaptor<StoredBodySupplier> bodySupplier =
        ArgumentCaptor.forClass(StoredBodySupplier.class);
    verify(mockRequestBodyDone).apply(eq(requestContext), bodySupplier.capture());
    assertEquals("body", bodySupplier.getValue().get());
    assertSame(mockFlow, flow);
  }

  @Test
  void withCorrectContentLength() {
    agentSpan = mock(AgentSpan.class);
    givenRequestContextAndCallbacks();

    assertNotNull(StoredBodyFactories.maybeCreateForByte(null, "1"));
    assertNotNull(StoredBodyFactories.maybeCreateForChar("1"));

    verifyRequestContextAndCallbacksRequestedTwice();
  }

  @Test
  void withCorrectContentLengthIntVersion() {
    agentSpan = mock(AgentSpan.class);
    givenRequestContextAndCallbacks();

    assertNotNull(StoredBodyFactories.maybeCreateForByte(null, 1));
    assertNotNull(StoredBodyFactories.maybeCreateForChar(1));

    verifyRequestContextAndCallbacksRequestedTwice();
  }

  @Test
  void withBadContentLength() {
    agentSpan = mock(AgentSpan.class);
    givenRequestContextAndCallbacks();

    assertNotNull(StoredBodyFactories.maybeCreateForByte(null, "foo"));
    assertNotNull(StoredBodyFactories.maybeCreateForChar("foo"));

    verifyRequestContextAndCallbacksRequestedTwice();
  }

  private void givenRequestContextAndCallbacks() {
    when(agentSpan.getRequestContext()).thenReturn(requestContext);
    when(callbackProvider.getCallback(EVENTS.requestBodyStart()))
        .thenReturn(mock(BiFunction.class));
    when(callbackProvider.getCallback(EVENTS.requestBodyDone())).thenReturn(mock(BiFunction.class));
  }

  private void verifyRequestContextAndCallbacksRequestedTwice() {
    verify(agentSpan, times(2)).getRequestContext();
    verify(callbackProvider, times(2)).getCallback(EVENTS.requestBodyStart());
    verify(callbackProvider, times(2)).getCallback(EVENTS.requestBodyDone());
  }
}
