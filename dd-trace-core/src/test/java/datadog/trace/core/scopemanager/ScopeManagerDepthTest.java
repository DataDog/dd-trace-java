package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.ScopeManagerTestBridge;
import datadog.trace.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

class ScopeManagerDepthTest extends DDCoreJavaSpecification {

  @Test
  void scopeManagerReturnsNoopScopeIfDepthExceeded() {
    // Using a local constant here to avoid classloading issues with ConfigDefaults
    int depth = 100;

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    ContinuableScopeManager scopeManager = ScopeManagerTestBridge.getScopeManager(tracer);

    // fill up the scope stack
    AgentScope scope = null;
    for (int i = 0; i < depth; i++) {
      AgentSpan testSpan = tracer.buildSpan("test", "test").start();
      scope = tracer.activateSpan(testSpan);
      assertInstanceOf(ContinuableScope.class, scope);
    }

    // last scope is still valid
    assertEquals(depth, scopeManager.scopeStack().depth());

    // activate span over limit
    AgentSpan span = tracer.buildSpan("test", "test").start();
    scope = tracer.activateSpan(span);

    // a noop instance is returned
    assertSame(noopScope(), scope);

    // activate a noop scope over the limit
    scope = scopeManager.activateManualSpan(noopSpan());

    // still have a noop instance
    assertSame(noopScope(), scope);

    // scope stack not effected
    assertEquals(depth, scopeManager.scopeStack().depth());

    scopeManager.scopeStack().clear();
    tracer.close();
  }

  @Test
  @WithConfig(key = TracerConfig.SCOPE_DEPTH_LIMIT, value = "0")
  void scopeManagerIgnoresDepthLimitWhenZero() {
    // Using a local constant here to avoid classloading issues with ConfigDefaults
    int defaultLimit = 100;

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    ContinuableScopeManager scopeManager = ScopeManagerTestBridge.getScopeManager(tracer);

    // fill up the scope stack
    AgentScope scope = null;
    for (int i = 0; i < defaultLimit; i++) {
      AgentSpan testSpan = tracer.buildSpan("test", "test").start();
      scope = tracer.activateSpan(testSpan);
      assertInstanceOf(ContinuableScope.class, scope);
    }

    // last scope is still valid
    assertEquals(defaultLimit, scopeManager.scopeStack().depth());

    // activate a scope
    AgentSpan span = tracer.buildSpan("test", "test").start();
    scope = tracer.activateSpan(span);

    // a real scope is returned
    assertNotSame(noopScope(), scope);
    assertEquals(defaultLimit + 1, scopeManager.scopeStack().depth());

    // activate a noop span
    scope = scopeManager.activateManualSpan(noopSpan());

    // a real instance is still returned
    assertNotSame(noopScope(), scope);

    // scope stack not effected
    assertEquals(defaultLimit + 2, scopeManager.scopeStack().depth());

    scopeManager.scopeStack().clear();
    tracer.close();
  }

  @Test
  void depthIsCorrectlyUpdatedWithOutOfOrderClosing() {
    // The decision here is that depth is the top-most open scope
    // Closed scopes that are not on top still count for depth

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    ContinuableScopeManager scopeManager = ScopeManagerTestBridge.getScopeManager(tracer);

    AgentSpan firstSpan = tracer.buildSpan("test", "foo").start();
    AgentScope firstScope = tracer.activateSpan(firstSpan);

    AgentSpan secondSpan = tracer.buildSpan("test", "foo").start();
    AgentScope secondScope = tracer.activateSpan(secondSpan);

    assertEquals(2, scopeManager.scopeStack().depth());

    firstSpan.finish();
    firstScope.close();

    assertEquals(2, scopeManager.scopeStack().depth());

    secondSpan.finish();
    secondScope.close();

    assertEquals(0, scopeManager.scopeStack().depth());

    tracer.close();
  }
}
