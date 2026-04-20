package datadog.trace.instrumentation.junit4.order;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Instrumentation;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.junit.runner.Description;

@AutoService(InstrumenterModule.class)
public class JUnit4TestOrdererInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private final String parentPackageName =
      Strings.getPackageName(JUnit4Instrumentation.class.getName());

  public JUnit4TestOrdererInstrumentation() {
    super("ci-visibility", "junit-4", "test-order");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String instrumentedType() {
    return "org.junit.runner.manipulation.Orderer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".SkippedByDatadog",
      parentPackageName + ".TracingListener",
      parentPackageName + ".JUnit4Utils",
      parentPackageName + ".TestEventsHandlerHolder",
      packageName + ".FailFastDescriptionComparator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("order"), JUnit4TestOrdererInstrumentation.class.getName() + "$OrderAdvice");
  }

  public static class OrderAdvice {
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "descriptions is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void onOrdering(@Advice.Return(readOnly = false) List<Description> descriptions) {
      String testOrder = Config.get().getCiVisibilityTestOrder();
      TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler =
          TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT4);
      if (CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder) && handler != null) {
        // sort descriptions after the user's sorting method (if any) has been applied
        List<Description> sorted = new ArrayList<Description>(descriptions);
        sorted.sort(new FailFastDescriptionComparator(handler));
        descriptions = sorted;
      } else {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }
    }
  }
}
