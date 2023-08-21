package datadog.trace.instrumentation.testng;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.testng.ClassMethodMap;
import org.testng.IMethodInstance;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.annotations.DataProvider;

@AutoService(Instrumenter.class)
public class TestNG64ClassListenerInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public TestNG64ClassListenerInstrumentation() {
    super("testng-64-class-listener");
  }

  @Override
  public String instrumentedType() {
    return "org.testng.internal.TestMethodWorker";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("invokeBeforeClassMethods")
            .and(takesArgument(0, named("org.testng.ITestClass")))
            .and(takesArgument(1, named("org.testng.IMethodInstance"))),
        TestNG64ClassListenerInstrumentation.class.getName() + "$InvokeBeforeClassAdvice");

    transformation.applyAdvice(
        named("invokeAfterClassMethods")
            .and(takesArgument(0, named("org.testng.ITestClass")))
            .and(takesArgument(1, named("org.testng.IMethodInstance"))),
        TestNG64ClassListenerInstrumentation.class.getName() + "$InvokeAfterClassAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestNGUtils",
      packageName + ".TestNGMethod",
      packageName + ".TestNGClassListener",
      packageName + ".TracingListener"
    };
  }

  public static class InvokeBeforeClassAdvice {
    @Advice.OnMethodEnter
    public static void invokeBeforeClass(
        @Advice.FieldValue("m_classMethodMap") final ClassMethodMap classMethodMap,
        @Advice.FieldValue("m_testContext") final ITestContext testContext,
        @Advice.Argument(0) final ITestClass testClass,
        @Advice.Argument(1) final IMethodInstance methodInstance) {

      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testContext);
      listener.onBeforeClass(testClass, classMethodMap, methodInstance);
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(final DataProvider dataProvider) {
      dataProvider.name();
    }
  }

  public static class InvokeAfterClassAdvice {
    @Advice.OnMethodExit
    public static void invokeAfterClass(
        @Advice.FieldValue("m_classMethodMap") final ClassMethodMap classMethodMap,
        @Advice.FieldValue("m_testContext") final ITestContext testContext,
        @Advice.Argument(0) final ITestClass testClass,
        @Advice.Argument(1) final IMethodInstance methodInstance) {

      TestNGClassListener listener = TestNGUtils.getTestNGClassListener(testContext);
      listener.onAfterClass(testClass, classMethodMap, methodInstance);
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(final DataProvider dataProvider) {
      dataProvider.name();
    }
  }
}
