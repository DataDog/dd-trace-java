package datadog.trace.instrumentation.testng6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.testng.TestNGClassListener;
import datadog.trace.instrumentation.testng.TestNGInstrumentation;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.util.Strings;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.testng.IMethodInstance;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.annotations.DataProvider;

@AutoService(InstrumenterModule.class)
public class TestNGClassListenerInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String commonPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGClassListenerInstrumentation() {
    super("ci-visibility", "testng", "testng-6");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // do not apply to TestNG 7.x and above
    // since those versions are handled by a different instrumentation
    return not(hasClassNamed("org.testng.annotations.CustomAttribute"));
  }

  @Override
  public String instrumentedType() {
    return "org.testng.internal.TestMethodWorker";
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

      boolean parallelized = TestNGUtils.isParallelized(testClass);
      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testContext);
      listener.invokeBeforeClass(testClass, parallelized);
    }

    // TestNG 6.4 and above
    public static String muzzleCheck(final DataProvider dataProvider) {
      return dataProvider.name();
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

    // TestNG 6.4 and above
    public static String muzzleCheck(final DataProvider dataProvider) {
      return dataProvider.name();
    }
  }
}
