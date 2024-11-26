package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import net.bytebuddy.asm.Advice;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runners.model.FrameworkMethod;

@AutoService(InstrumenterModule.class)
public class JUnit4BeforeAfterInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes {

  public JUnit4BeforeAfterInstrumentation() {
    super("ci-visibility", "junit-4", "setup-teardown");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.junit.internal.runners.statements.RunBefores",
      "org.junit.internal.runners.statements.RunAfters"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("invokeMethod")
            .and(takesArgument(0, named("org.junit.runners.model.FrameworkMethod"))),
        JUnit4BeforeAfterInstrumentation.class.getName() + "$RunBeforesAftersAdvice");
  }

  public static class RunBeforesAftersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startCallSpan(@Advice.Argument(0) final FrameworkMethod method) {
      final AgentSpan span = AgentTracer.startSpan("junit", method.getMethod().getName());
      if (method.getMethod().isAnnotationPresent(Before.class)) {
        span.setTag(Tags.TEST_CALLBACK, "Before");
      } else if (method.getMethod().isAnnotationPresent(After.class)) {
        span.setTag(Tags.TEST_CALLBACK, "After");
      } else if (method.getMethod().isAnnotationPresent(BeforeClass.class)) {
        span.setTag(Tags.TEST_CALLBACK, "BeforeClass");
      } else if (method.getMethod().isAnnotationPresent(AfterClass.class)) {
        span.setTag(Tags.TEST_CALLBACK, "AfterClass");
      }
      return AgentTracer.activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishCallSpan(@Advice.Enter final AgentScope scope) {
      AgentSpan span = scope.span();
      scope.close();
      span.finish();
    }
  }
}
