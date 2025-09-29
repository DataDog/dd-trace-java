package datadog.trace.instrumentation.maven.surefire.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Instrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.instrumentation.junit4.order.JUnit4FailFastClassOrderer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.apache.maven.surefire.api.util.TestsToRun;

@AutoService(InstrumenterModule.class)
public class JUnit4ClassOrderInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public JUnit4ClassOrderInstrumentation() {
    super("ci-visibility", "maven", "surefire", "junit4");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.maven.surefire.junit4.JUnit4Provider",
      "org.apache.maven.surefire.junitcore.JUnitCoreProvider",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      JUnit4Instrumentation.class.getPackage().getName() + ".JUnit4Utils",
      JUnit4Instrumentation.class.getPackage().getName() + ".TestEventsHandlerHolder",
      JUnit4Instrumentation.class.getPackage().getName() + ".SkippedByDatadog",
      JUnit4Instrumentation.class.getPackage().getName() + ".TracingListener",
      JUnit4Instrumentation.class.getPackage().getName() + ".order.JUnit4FailFastClassOrderer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setTestsToRun"),
        JUnit4ClassOrderInstrumentation.class.getName() + "$TestsToRunAdvice");
  }

  public static class TestsToRunAdvice {
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "testsToRun is the field value modified by the advice")
    @Advice.OnMethodExit
    public static void onSetTestsToRun(
        @Advice.FieldValue(value = "testsToRun", readOnly = false) TestsToRun testsToRun) {
      String testOrder = Config.get().getCiVisibilityTestOrder();
      if (!CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder)) {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }

      List<Class<?>> testClasses = new ArrayList<>();

      for (Class<?> testClass : testsToRun) {
        testClasses.add(testClass);
        TestFrameworkInstrumentation framework = JUnit4Utils.classToFramework(testClass);
        if (framework == TestFrameworkInstrumentation.JUNIT4) {
          TestEventsHandlerHolder.start(framework, JUnit4Utils.capabilities(true));
        }
      }

      testClasses.sort(
          new JUnit4FailFastClassOrderer(
              TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT4)));

      testsToRun = new TestsToRun(new LinkedHashSet<>(testClasses));
    }
  }
}
