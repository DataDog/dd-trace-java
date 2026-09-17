package datadog.trace.agent.tooling.async;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.singleton;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.reflect.InvocationTargetException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncPropagationSuppressionTest {
  private AgentTracer.TracerAPI previousTracer;
  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    previousTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(previousTracer);
    tracer.close();
  }

  @Test
  void restoresPropagationAfterNestedAndThrowingCalls() throws Exception {
    Class<?> fixture = instrument(Methods.class);
    AgentSpan span = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(span)) {
      setAsyncPropagationEnabled(true);
      assertEquals(false, fixture.getMethod("nested").invoke(null));
      assertTrue(isAsyncPropagationEnabled());

      RuntimeException failure = new RuntimeException("application failure");
      InvocationTargetException thrown =
          assertThrows(
              InvocationTargetException.class,
              () -> fixture.getMethod("fail", RuntimeException.class).invoke(null, failure));
      assertSame(failure, thrown.getCause());
      assertTrue(isAsyncPropagationEnabled());
    } finally {
      span.finish();
    }
  }

  @Test
  void preservesAlreadyDisabledPropagationAndMissingScope() throws Exception {
    Class<?> fixture = instrument(Methods.class);
    assertEquals(false, fixture.getMethod("nested").invoke(null));
    assertFalse(isAsyncPropagationEnabled());
    AgentSpan span = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(span)) {
      setAsyncPropagationEnabled(false);
      assertEquals(false, fixture.getMethod("nested").invoke(null));
      assertFalse(isAsyncPropagationEnabled());
    } finally {
      span.finish();
    }
  }

  @Test
  void suppressesStaticInitializationAndRestoresPropagation() throws Exception {
    AgentSpan span = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(span)) {
      setAsyncPropagationEnabled(true);
      Class<?> fixture = instrument(Initializer.class);
      assertEquals(false, fixture.getField("propagating").get(null));
      assertTrue(isAsyncPropagationEnabled());
    } finally {
      span.finish();
    }
  }

  @Test
  @WithConfig(key = "trace.rxjava.enabled", value = "false")
  @WithConfig(key = "trace.java_concurrent.enabled", value = "true")
  void followsContextTrackingRatherThanLibraryTracing() {
    TestSuppression suppression = new TestSuppression();
    assertTrue(suppression.isEnabled());
    assertTrue(suppression.isApplicable(singleton(TargetSystem.CONTEXT_TRACKING)));
    assertFalse(suppression.isApplicable(singleton(TargetSystem.TRACING)));
  }

  @Test
  @WithConfig(key = "trace.java_concurrent.enabled", value = "false")
  void honorsExecutorDisablement() {
    assertFalse(new TestSuppression().isEnabled());
  }

  private static Class<?> instrument(Class<?> fixture) {
    DynamicType.Builder<?>[] builder = {new ByteBuddy().redefine(fixture)};
    new TestSuppression()
        .methodAdvice(
            (matcher, advice, additional) -> {
              try {
                builder[0] = builder[0].visit(Advice.to(Class.forName(advice)).on(matcher));
              } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
              }
            });
    return builder[0]
        .make()
        .load(fixture.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
        .getLoaded();
  }

  private static class TestSuppression extends AsyncPropagationSuppressingInstrumentation
      implements Instrumenter.ForSingleType {
    @Override
    public String instrumentedType() {
      return Methods.class.getName();
    }

    @Override
    protected ElementMatcher<? super MethodDescription> suppressedMethods() {
      return isMethod()
          .and(named("observe").or(named("nested")).or(named("fail")))
          .or(isTypeInitializer());
    }
  }

  public static class Methods {
    public static boolean observe() {
      return isAsyncPropagationEnabled();
    }

    public static boolean nested() {
      if (observe()) {
        throw new AssertionError("Nested call propagated context");
      }
      return isAsyncPropagationEnabled();
    }

    public static void fail(RuntimeException failure) {
      if (isAsyncPropagationEnabled()) {
        throw new AssertionError("Throwing call propagated context");
      }
      throw failure;
    }
  }

  public static class Initializer {
    public static boolean propagating = isAsyncPropagationEnabled();
  }
}
