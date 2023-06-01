package datadog.trace.instrumentation.testng75;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.testng.TestNGClassListener;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.util.Strings;
import net.bytebuddy.asm.Advice;
import org.testng.IMethodInstance;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.internal.invokers.TestMethodWorker;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

@AutoService(Instrumenter.class)
public class TestNGClassListenerInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGClassListenerInstrumentation() {
    super("testng-75-class-listener");
  }

  @Override
  public String instrumentedType() {
    return "org.testng.internal.invokers.TestMethodWorker";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("invokeBeforeClassMethods")
            .and(takesArgument(0, named("org.testng.ITestClass")))
            .and(takesArgument(1, named("org.testng.IMethodInstance"))),
        TestNGClassListenerInstrumentation.class.getName() + "$InvokeBeforeClassAdvice");

    transformation.applyAdvice(
        named("invokeAfterClassMethods")
            .and(takesArgument(0, named("org.testng.ITestClass")))
            .and(takesArgument(1, named("org.testng.IMethodInstance"))),
        TestNGClassListenerInstrumentation.class.getName() + "$InvokeAfterClassAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      commonPackageName + ".TestNGUtils",
      commonPackageName + ".TestNGClassListener",
      commonPackageName + ".TracingListener"
    };
  }

  public static class InvokeBeforeClassAdvice {
    @Advice.OnMethodEnter
    public static void invokeBeforeClass(
        @Advice.FieldValue("m_testContext") final ITestContext testContext,
        @Advice.Argument(0) final ITestClass testClass) {

      XmlTest xmlTest = testClass.getXmlTest();
      XmlSuite.ParallelMode parallel = xmlTest.getParallel();
      boolean parallelized =
          parallel == XmlSuite.ParallelMode.METHODS || parallel == XmlSuite.ParallelMode.TESTS;

      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testContext);
      listener.invokeBeforeClass(testClass, parallelized);
    }

    // TestNG 7.5 and above
    public static void muzzleCheck(final TestMethodWorker testMethodWorker) {
      testMethodWorker.completed();
    }
  }

  public static class InvokeAfterClassAdvice {
    @Advice.OnMethodExit
    public static void invokeAfterClass(
        @Advice.FieldValue("m_testContext") final ITestContext testContext,
        @Advice.Argument(0) final ITestClass testClass,
        @Advice.Argument(1) final IMethodInstance methodInstance) {

      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testContext);
      listener.invokeAfterClass(testClass, methodInstance);
    }

    // TestNG 7.5 and above
    public static void muzzleCheck(final TestMethodWorker testMethodWorker) {
      testMethodWorker.completed();
    }
  }
}
