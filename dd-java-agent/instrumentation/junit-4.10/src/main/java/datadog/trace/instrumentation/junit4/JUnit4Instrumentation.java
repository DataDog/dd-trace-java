package datadog.trace.instrumentation.junit4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit4.JUnit4Decorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

@AutoService(Instrumenter.class)
public class JUnit4Instrumentation extends Instrumenter.Default {

  public JUnit4Instrumentation() {
    super("junit", "junit-4");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.junit.runner.notification.RunNotifier");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JUnit4Decorator"};
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

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isTraceTestsEnabled();
  }

  public static class TestStartedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void startTest(@Advice.Argument(0) final Description description) {
      if (DECORATE.skipTrace(description)) {
        return;
      }

      final AgentSpan span = startSpan("junit.test");
      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);

      DECORATE.afterStart(span);
      DECORATE.onTestStart(span, description);
    }
  }

  public static class TestFinishedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void finishTest(@Advice.Argument(0) final Description description) {
      if (DECORATE.skipTrace(description)) {
        return;
      }

      final AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        return;
      }

      final TraceScope scope = AgentTracer.activeScope();
      if (scope != null) {
        scope.close();
      }

      DECORATE.onTestFinish(span);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public static class TestFailureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void failureTest(@Advice.Argument(0) final Failure failure) {
      if (DECORATE.skipTrace(failure.getDescription())) {
        return;
      }

      final AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        return;
      }

      DECORATE.onTestFailure(span, failure);
    }
  }

  public static class TestIgnoredAdvice {
    @Advice.OnMethodExit
    public static void ignoreTest(@Advice.Argument(0) final Description description) {
      if (DECORATE.skipTrace(description)) {
        return;
      }

      final List<String> testNames = new ArrayList<>();
      if (description.getMethodName() != null && !"".equals(description.getMethodName())) {
        testNames.add(description.getMethodName());
      } else {
        // If @Ignore annotation is kept at class level, the instrumentation
        // reports every method annotated with @Test as skipped test.
        testNames.addAll(DECORATE.testNames(description.getTestClass(), Test.class));
      }

      for (final String testName : testNames) {
        final AgentSpan span = startSpan("junit.test");
        DECORATE.afterStart(span);
        DECORATE.onTestIgnored(span, description, testName);
        DECORATE.beforeFinish(span);
        span.finish(span.getStartTime());
      }
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
