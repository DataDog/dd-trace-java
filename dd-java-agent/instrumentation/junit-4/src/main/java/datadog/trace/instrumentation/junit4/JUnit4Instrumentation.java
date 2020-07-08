package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit4.JUnit4Decorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

@AutoService(Instrumenter.class)
public class JUnit4Instrumentation extends Instrumenter.Default {

  public JUnit4Instrumentation() {
    super("junit", "junit-4");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.junit.runner.Description", TestState.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.junit.runner.notification.RunNotifier");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.junit.runner.notification.RunNotifier");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JUnit4Decorator", packageName + ".TestState"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("fireTestStarted"), JUnit4Instrumentation.class.getName() + "$TestStartedAdvice");
    transformers.put(
        named("fireTestFinished"), JUnit4Instrumentation.class.getName() + "$TestFinishedAdvice");
    transformers.put(
        named("fireTestFailure"), JUnit4Instrumentation.class.getName() + "$TestFailureAdvice");
    transformers.put(
        named("fireTestIgnored"), JUnit4Instrumentation.class.getName() + "$TestIgnoredAdvice");
    return transformers;
  }

  public static class TestStartedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void startTest(@Advice.Argument(0) final Description description) {
      if (description.getAnnotation(DisableTestTrace.class) != null) {
        return;
      }

      final AgentSpan span = startSpan(description.getMethodName());
      final AgentScope scope = activateSpan(span);

      DECORATE.afterStart(span);
      DECORATE.onTestStart(span, description);

      final TestState testState = new TestState(span);
      testState.setTestScope(scope);

      final ContextStore<Description, TestState> contextStore =
          InstrumentationContext.get(Description.class, TestState.class);
      contextStore.putIfAbsent(description, testState);
    }
  }

  public static class TestFinishedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void finishTest(@Advice.Argument(0) final Description description) {
      if (description.getAnnotation(DisableTestTrace.class) != null) {
        return;
      }

      final ContextStore<Description, TestState> contextStore =
          InstrumentationContext.get(Description.class, TestState.class);
      final TestState testState = contextStore.get(description);

      if (testState == null || testState.getTestSpan() == null) {
        return;
      }

      if (testState.getTestScope() != null) {
        testState.getTestScope().close();
      }

      final AgentSpan span = testState.getTestSpan();
      DECORATE.onTestFinish(span);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public static class TestFailureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void failureTest(@Advice.Argument(0) final Failure failure) {
      if (failure.getDescription().getAnnotation(DisableTestTrace.class) != null) {
        return;
      }

      final ContextStore<Description, TestState> contextStore =
          InstrumentationContext.get(Description.class, TestState.class);
      final TestState testState = contextStore.get(failure.getDescription());

      if (testState == null || testState.getTestSpan() == null) {
        return;
      }

      final AgentSpan span = testState.getTestSpan();
      DECORATE.onTestFailure(span, failure);
    }
  }

  public static class TestIgnoredAdvice {
    @Advice.OnMethodExit
    public static void ignoreTest(@Advice.Argument(0) final Description description) {
      if (description.getAnnotation(DisableTestTrace.class) != null) {
        return;
      }

      final AgentSpan span = startSpan(description.getMethodName());
      DECORATE.afterStart(span);
      DECORATE.onTestIgnored(span, description);
      DECORATE.beforeFinish(span);
      span.finish(span.getStartTime());
    }
  }
}
