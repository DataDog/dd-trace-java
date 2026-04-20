package datadog.trace.instrumentation.testng7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.testng.TestNGClassListener;
import datadog.trace.instrumentation.testng.TestNGInstrumentation;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.util.Strings;
import net.bytebuddy.asm.Advice;
import org.testng.IMethodInstance;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.annotations.CustomAttribute;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

@AutoService(InstrumenterModule.class)
public class TestNGClassListenerInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGClassListenerInstrumentation() {
    super("ci-visibility", "testng", "testng-7");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.testng.internal.TestMethodWorker", // TestNG 7.0-7.4
      "org.testng.internal.invokers.TestMethodWorker" // TestNG 7.5+
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("invokeBeforeClassMethods")
            .and(takesArgument(0, named("org.testng.ITestClass")))
            .and(takesArgument(1, named("org.testng.IMethodInstance"))),
        TestNGClassListenerInstrumentation.class.getName() + "$InvokeBeforeClassAdvice");

    transformer.applyAdvice(
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

  @Override
  public int order() {
    // Depends on datadog.trace.instrumentation.testng.TestNGInstrumentation,
    // as it needs datadog.trace.instrumentation.testng.TestEventsHandlerHolder.start to be called;
    return TestNGInstrumentation.ORDER - 1;
  }

  public static class InvokeBeforeClassAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
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

    // TestNG 7.0 and above
    public static String muzzleCheck(final CustomAttribute customAttribute) {
      return customAttribute.name();
    }
  }

  public static class InvokeAfterClassAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodExit
    public static void invokeAfterClass(
        @Advice.FieldValue("m_testContext") final ITestContext testContext,
        @Advice.Argument(0) final ITestClass testClass,
        @Advice.Argument(1) final IMethodInstance methodInstance) {

      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testContext);
      listener.invokeAfterClass(testClass, methodInstance);
    }

    // TestNG 7.0 and above
    public static String muzzleCheck(final CustomAttribute customAttribute) {
      return customAttribute.name();
    }
  }
}
