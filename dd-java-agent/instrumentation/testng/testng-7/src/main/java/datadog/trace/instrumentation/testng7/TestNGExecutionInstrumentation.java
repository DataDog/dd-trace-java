package datadog.trace.instrumentation.testng7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.instrumentation.testng.execution.RetryAnalyzer;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

@AutoService(InstrumenterModule.class)
public class TestNGExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGExecutionInstrumentation() {
    super("ci-visibility", "testng", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.testng.internal.invokers.TestInvoker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("shouldRetryTestMethod").and(takesArgument(1, named("org.testng.ITestResult"))),
        TestNGExecutionInstrumentation.class.getName() + "$ExecutionAdvice");

    transformer.applyAdvice(
        named("runTestResultListener").and(takesArgument(0, named("org.testng.ITestResult"))),
        TestNGExecutionInstrumentation.class.getName() + "$SuppressFailuresAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      commonPackageName + ".TestNGUtils",
      commonPackageName + ".TestEventsHandlerHolder",
      commonPackageName + ".TestNGClassListener",
      commonPackageName + ".execution.RetryAnalyzer",
    };
  }

  public static class ExecutionAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodExit
    public static void shouldRetryTestMethod(
        @Advice.Argument(1) final ITestResult result,
        @Advice.Return(readOnly = false) boolean retry) {
      if (!retry && result.isSuccess()) {
        IRetryAnalyzer retryAnalyzer = TestNGUtils.getRetryAnalyzer(result);
        if (retryAnalyzer instanceof RetryAnalyzer) {
          retry = retryAnalyzer.retry(result);
        }
      }
    }
  }

  public static class SuppressFailuresAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodEnter
    public static void suppressFailures(@Advice.Argument(0) final ITestResult result) {
      if (result.getStatus() != ITestResult.FAILURE) {
        // nothing to suppress
        return;
      }

      IRetryAnalyzer retryAnalyzer = TestNGUtils.getRetryAnalyzer(result);
      if (!(retryAnalyzer instanceof RetryAnalyzer)) {
        // test execution policies not injected
        return;
      }
      RetryAnalyzer ddRetryAnalyzer = (RetryAnalyzer) retryAnalyzer;
      if (ddRetryAnalyzer.suppressFailures()) {
        // "failed but within success percentage"
        result.setStatus(ITestResult.SUCCESS_PERCENTAGE_FAILURE);
      }
    }
  }
}
