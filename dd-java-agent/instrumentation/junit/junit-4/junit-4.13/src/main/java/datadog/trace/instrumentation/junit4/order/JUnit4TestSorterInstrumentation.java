package datadog.trace.instrumentation.junit4.order;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

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
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.runner.manipulation.Sorter;

@AutoService(InstrumenterModule.class)
public class JUnit4TestSorterInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String parentPackageName =
      Strings.getPackageName(JUnit4Instrumentation.class.getName());

  public JUnit4TestSorterInstrumentation() {
    super("ci-visibility", "junit-4", "test-order");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runner.Runner";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        .and(implementsInterface(named("org.junit.runner.manipulation.Sortable")));
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
        named("sort").and(takesArgument(0, named("org.junit.runner.manipulation.Sorter"))),
        JUnit4TestSorterInstrumentation.class.getName() + "$SorterAdvice");
  }

  public static class SorterAdvice {
    @Advice.OnMethodEnter
    public static void onOrdering(@Advice.Argument(value = 0, readOnly = false) Sorter sorter) {
      String testOrder = Config.get().getCiVisibilityTestOrder();
      TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler =
          TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT4);
      if (CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder) && handler != null) {
        // use sorter provided when elements are equal (same execution priority)
        sorter = new Sorter(new FailFastDescriptionComparator(handler).thenComparing(sorter));
      } else {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }
    }
  }
}
