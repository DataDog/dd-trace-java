package datadog.trace.api.iast;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IastContextTest {

  private static final TracerAPI ORIGINAL_TRACER = AgentTracer.get();

  private TracerAPI tracer;
  private IastContext iastCtx;
  private AgentSpan span;
  private IastContext.Provider originalProvider;

  @BeforeEach
  void setup() throws ReflectiveOperationException {
    originalProvider = readContextProvider();
    tracer = mock(TracerAPI.class);
    iastCtx = mock(IastContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.IAST)).thenReturn(iastCtx);
    span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void cleanup() throws ReflectiveOperationException {
    AgentTracer.forceRegister(ORIGINAL_TRACER);
    writeContextProvider(originalProvider);
  }

  @Test
  void testGetContext() {
    when(tracer.activeSpan()).thenReturn(span, (AgentSpan) null);

    IastContext context = IastContext.Provider.get();

    verify(tracer).activeSpan();
    assertSame(iastCtx, context);

    IastContext nullContext = IastContext.Provider.get();

    verify(tracer, times(2)).activeSpan();
    assertNull(nullContext);
  }

  @Test
  void testGetContextWithSpan() {
    IastContext context = IastContext.Provider.get(span);

    assertSame(iastCtx, context);
  }

  @Test
  void testGetContextWithProviderInstance() {
    IastContext.Provider provider = mock(IastContext.Provider.class);
    IastContext.Provider.register(provider);

    IastContext.Provider.get();

    verify(provider).resolve();
  }

  // IastContext.Provider.INSTANCE is a private static field; snapshot and restore it reflectively
  // so the registered provider does not leak into other tests
  private static IastContext.Provider readContextProvider() throws ReflectiveOperationException {
    return (IastContext.Provider) contextProviderField().get(null);
  }

  private static void writeContextProvider(IastContext.Provider provider)
      throws ReflectiveOperationException {
    contextProviderField().set(null, provider);
  }

  private static Field contextProviderField() throws NoSuchFieldException {
    Field field = IastContext.Provider.class.getDeclaredField("INSTANCE");
    field.setAccessible(true);
    return field;
  }
}
