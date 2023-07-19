package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.SkippableTest;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;

@AutoService(Instrumenter.class)
public class JUnit5ItrInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit5ItrInstrumentation() {
    super("junit", "junit-5");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityItrEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.platform.engine.support.hierarchical.Node";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(implementsInterface(named("org.junit.platform.engine.TestDescriptor")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnit5Utils",
      packageName + ".TestFrameworkUtils",
      packageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("shouldBeSkipped").and(takesArguments(1)),
        JUnit5ItrInstrumentation.class.getName() + "$JUnit5ItrAdvice");
  }

  public static class JUnit5ItrAdvice {

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "skipResult is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void shouldBeSkipped(
        @Advice.This TestDescriptor testDescriptor,
        @Advice.Return(readOnly = false) Node.SkipResult skipResult) {
      if (skipResult.isSkipped()) {
        return;
      }

      SkippableTest test = TestFrameworkUtils.toSkippableTest(testDescriptor);
      if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skip(test)) {
        skipResult = Node.SkipResult.skip(InstrumentationBridge.ITR_SKIP_REASON);
      }
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
