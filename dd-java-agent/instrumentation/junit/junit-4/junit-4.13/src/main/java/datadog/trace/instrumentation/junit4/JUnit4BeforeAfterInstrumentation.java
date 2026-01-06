package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.junit.runner.manipulation.InvalidOrderingException;
import org.junit.runner.manipulation.Ordering;
import org.junit.runners.model.FrameworkMethod;

@AutoService(InstrumenterModule.class)
public class JUnit4BeforeAfterInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public JUnit4BeforeAfterInstrumentation() {
    super("ci-visibility", "junit-4", "setup-teardown");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.junit.internal.runners.statements.RunBefores",
      "org.junit.internal.runners.statements.RunAfters",
      "org.junit.runners.parameterized.BlockJUnit4ClassRunnerWithParameters$RunBeforeParams",
      "org.junit.runners.parameterized.BlockJUnit4ClassRunnerWithParameters$RunAfterParams",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnit4BeforeAfterOperationsTracer",
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
      return JUnit4BeforeAfterOperationsTracer.startTrace(method.getMethod());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishCallSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      JUnit4BeforeAfterOperationsTracer.endTrace(scope, throwable);
    }

    // JUnit 4.13 and above
    public static void muzzleCheck(final Ordering ord) {
      try {
        ord.apply(null);
      } catch (InvalidOrderingException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
