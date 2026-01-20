package datadog.trace.instrumentation.testng7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.instrumentation.testng.TracingListener;
import datadog.trace.instrumentation.testng.execution.RetryAnalyzer;
import datadog.trace.util.Strings;
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import org.testng.IRetryAnalyzer;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.internal.ITestResultNotifier;
import org.testng.internal.TestListenerHelper;

@AutoService(InstrumenterModule.class)
public class TestNGExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGExecutionInstrumentation() {
    super("ci-visibility", "testng", "test-retry");
  }

  @Override
  public boolean isEnabled() {
    return Config.get().isCiVisibilityExecutionPoliciesEnabled();
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
      commonPackageName + ".TracingListener",
    };
  }

  public static class ExecutionAdvice {
    @Advice.OnMethodEnter
    public static void alignBeforeRetry(
        @Advice.FieldValue(value = "m_notifier") final ITestResultNotifier resultNotifier,
        @Advice.Argument(1) final ITestResult result) {
      IRetryAnalyzer retryAnalyzer = TestNGUtils.getRetryAnalyzer(result);
      if (retryAnalyzer instanceof RetryAnalyzer) {
        // If DD's retry analyzer is used, create the execution history and report the test result
        // beforehand to the tracing listener to align execution ordering with other frameworks.
        // Also avoids TestNG marking retried tests as skipped
        RetryAnalyzer ddRetryAnalyzer = (RetryAnalyzer) retryAnalyzer;
        ddRetryAnalyzer.createExecutionPolicy(result);

        ITestListener tracingListener = null;
        for (ITestListener listener : resultNotifier.getTestListeners()) {
          if (listener instanceof TracingListener) {
            tracingListener = listener;
          }
        }

        // Test reporting is idempotent due to only working for in progress tests. Once a test is
        // reported it is not considered in progress anymore. DD's test listener will be asked by
        // the framework to report the test again after the retry logic is executed, but it will
        // result in a no-op, avoiding double reporting
        TestListenerHelper.runTestListeners(result, Collections.singletonList(tracingListener));

        // Also set suppress failures beforehand to align execution ordering.
        ddRetryAnalyzer.setSuppressFailures(result);
      }
    }

    @SuppressWarnings("bytebuddy-exception-suppression")
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
      if (ddRetryAnalyzer.getAndResetSuppressFailures()) {
        // "failed but within success percentage"
        result.setStatus(ITestResult.SUCCESS_PERCENTAGE_FAILURE);
      }
    }
  }
}
