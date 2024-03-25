package datadog.trace.instrumentation.testng7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.instrumentation.testng.retry.RetryAnalyzer;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.testng.IRetryAnalyzer;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

@AutoService(InstrumenterModule.class)
public class TestNGRetryInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGRetryInstrumentation() {
    super("ci-visibility", "testng", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityTestRetryEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.testng.internal.invokers.TestInvoker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("shouldRetryTestMethod").and(takesArgument(1, named("org.testng.ITestResult"))),
        TestNGRetryInstrumentation.class.getName() + "$RetryAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      commonPackageName + ".TestNGUtils",
      commonPackageName + ".TestEventsHandlerHolder",
      commonPackageName + ".TestNGClassListener",
      commonPackageName + ".retry.RetryAnalyzer",
    };
  }

  public static class RetryAdvice {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodExit
    public static void shouldRetryTestMethod(
        @Advice.Argument(1) final ITestResult result,
        @Advice.Return(readOnly = false) boolean retry) {
      if (!retry && result.isSuccess()) {
        ITestNGMethod method = result.getMethod();
        IRetryAnalyzer retryAnalyzer = method.getRetryAnalyzer(result);
        if (retryAnalyzer instanceof RetryAnalyzer) {
          retry = retryAnalyzer.retry(result);
        }
      }
    }
  }
}
