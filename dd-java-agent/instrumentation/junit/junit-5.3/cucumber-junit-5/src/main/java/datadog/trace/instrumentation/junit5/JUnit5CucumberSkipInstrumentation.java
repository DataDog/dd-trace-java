package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;

@AutoService(InstrumenterModule.class)
public class JUnit5CucumberSkipInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JUnit5CucumberSkipInstrumentation() {
    super("ci-visibility", "junit-5", "junit-5-cucumber");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("io.cucumber.junit.platform.engine.CucumberTestEngine");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && (Config.get().isCiVisibilityTestSkippingEnabled()
            || Config.get().isCiVisibilityTestManagementEnabled());
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.cucumber.junit.platform.engine.CucumberTestEngine";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("io.cucumber.junit.platform.engine.NodeDescriptor"))
        // legacy Cucumber versions
        .or(extendsClass(named("io.cucumber.junit.platform.engine.PickleDescriptor")))
        // Cucumber 7.24+
        .or(
            extendsClass(
                named(
                    "io.cucumber.junit.platform.engine.CucumberTestDescriptor$PickleDescriptor")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestDataFactory",
      packageName + ".JUnitPlatformUtils",
      packageName + ".CucumberUtils",
      packageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("shouldBeSkipped").and(takesArguments(1)),
        JUnit5CucumberSkipInstrumentation.class.getName() + "$JUnit5SkipAdvice");
  }

  /**
   * !!!!!!!!!!!!!!!! IMPORTANT !!!!!!!!!!!!!!!! Do not use or refer to any classes from {@code
   * org.junit.platform.launcher} package in here: in some Gradle projects this package is not
   * available in CL where this instrumentation is injected
   */
  public static class JUnit5SkipAdvice {

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

      TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler =
          TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.CUCUMBER);

      if (testEventsHandler == null) {
        // should only happen in integration tests
        // because we cannot avoid instrumenting ourselves
        return;
      }

      TestIdentifier test = CucumberUtils.toTestIdentifier(testDescriptor);
      if (test == null) {
        return;
      }

      SkipReason skipReason = testEventsHandler.skipReason(test);
      if (skipReason == null) {
        return;
      }

      if (skipReason == SkipReason.ITR) {
        Collection<TestTag> tags = testDescriptor.getTags();
        for (TestTag tag : tags) {
          if (CIConstants.Tags.ITR_UNSKIPPABLE_TAG.equals(tag.getName())) {
            return;
          }
        }
      }

      skipResult = Node.SkipResult.skip(skipReason.getDescription());
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
