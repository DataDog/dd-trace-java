package datadog.trace.instrumentation.testng64;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.testng.TestNGClassListener;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.util.Strings;
import net.bytebuddy.asm.Advice;
import org.testng.ITestNGMethod;
import org.testng.TestRunner;
import org.testng.annotations.DataProvider;

@AutoService(Instrumenter.class)
public class TestNGTestRunnerInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGTestRunnerInstrumentation() {
    super("testng-64-test-runner");
  }

  @Override
  public String instrumentedType() {
    return "org.testng.TestRunner";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("run").and(takesNoArguments()),
        TestNGTestRunnerInstrumentation.class.getName() + "$InvokeRunAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      commonPackageName + ".TestNGUtils",
      commonPackageName + ".TestNGClassListener",
      commonPackageName + ".TracingListener"
    };
  }

  public static class InvokeRunAdvice {
    @Advice.OnMethodEnter
    public static void invokeRun(@Advice.This final TestRunner testRunner) {
      ITestNGMethod[] testMethods = testRunner.getAllTestMethods();
      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testRunner);
      listener.registerTestMethods(testMethods);
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(
        final DataProvider dataProvider,
        final org.testng.internal.TestMethodWorker testMethodWorker) {
      testMethodWorker.getTestResults();
      dataProvider.name();
    }
  }
}
