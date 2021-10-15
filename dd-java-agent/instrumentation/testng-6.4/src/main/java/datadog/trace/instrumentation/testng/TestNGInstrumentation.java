package datadog.trace.instrumentation.testng;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.testng.ITestListener;
import org.testng.ITestNGListener;
import org.testng.TestNG;
import org.testng.annotations.DataProvider;

@AutoService(Instrumenter.class)
public class TestNGInstrumentation extends Instrumenter.Tracing {

  public TestNGInstrumentation() {
    super("testng");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.testng.TestNG");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("initializeDefaultListeners"),
        TestNGInstrumentation.class.getName() + "$TestNGAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".TestNGDecorator", packageName + ".TracingListener"};
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  public static class TestNGAdvice {
    @Advice.OnMethodExit
    public static void addTracingListener(@Advice.This final TestNG testNG) {
      for (final ITestListener testListener : testNG.getTestListeners()) {
        if (testListener instanceof TracingListener) {
          return;
        }
      }

      final Package pkg = TestNG.class.getPackage();
      final String version =
          pkg.getImplementationVersion() != null
              ? pkg.getImplementationVersion()
              : pkg.getSpecificationVersion();
      final TracingListener tracingListener = new TracingListener(version);
      testNG.addListener((ITestNGListener) tracingListener);
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(final DataProvider dataProvider) {
      dataProvider.name();
    }
  }
}
