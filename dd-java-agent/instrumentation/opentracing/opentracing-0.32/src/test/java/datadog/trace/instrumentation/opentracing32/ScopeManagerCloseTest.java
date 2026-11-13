package datadog.trace.instrumentation.opentracing32;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.opentracing.DefaultLogHandler;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScopeManagerCloseTest extends DDJavaSpecification {
  private CoreTracer tracer;
  private AgentTracer.TracerAPI previousTracer;
  private ContextHandle previousContext;
  private DDSpan parent;
  private DDSpan child;
  private OTScopeManager manager;
  private TypeConverter converter;

  @BeforeEach
  void setUpContext() {
    previousTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    AgentTracer.installLegacyContextManager();
    previousContext = Context.current().checkpoint();
    parent = (DDSpan) tracer.startSpan("test", "parent");
    parent.swap();
    Context.setCaptureEnabled(false);
    child = (DDSpan) tracer.startSpan("test", "child");
    converter = new TypeConverter(new DefaultLogHandler());
    manager = new OTScopeManager(tracer, converter);
  }

  @AfterEach
  void restoreContext() {
    previousContext.swap();
    child.finish();
    parent.finish();
    tracer.close();
    AgentTracer.forceRegister(previousTracer);
  }

  @Test
  @WithConfig(key = "legacy.context-manager.enabled", value = "false")
  void activeViewLeavesContextIntactAndRetainedScopeRestoresIt() {
    Scope scope = manager.activate(converter.toSpan(child), true);
    try {
      Context.setCaptureEnabled(false);
      manager.active().close();
      manager.active().close();
      assertSame(child, tracer.activeSpan());
      assertFalse(Context.isCaptureEnabled());
      assertFalse(child.isFinished());
    } finally {
      scope.close();
    }
    assertSame(parent, tracer.activeSpan());
    assertFalse(Context.isCaptureEnabled());
    assertTrue(child.isFinished());
  }

  @Test
  @WithConfig(key = "legacy.context-manager.enabled", value = "true")
  void legacyActiveViewClosesTheCurrentScopeWithoutFinishingTheSpan() {
    Scope scope = manager.activate(converter.toSpan(child), true);
    try {
      manager.active().close();
      assertSame(parent, tracer.activeSpan());
      assertFalse(Context.isCaptureEnabled());
      assertFalse(child.isFinished());
    } finally {
      scope.close();
    }
    assertSame(parent, tracer.activeSpan());
    assertTrue(child.isFinished());
  }
}
