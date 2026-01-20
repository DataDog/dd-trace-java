package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cucumber.core.gherkin.Pickle;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.AssumptionViolatedException;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

@AutoService(InstrumenterModule.class)
public class JUnit4CucumberSkipInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JUnit4CucumberSkipInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-cucumber");
  }

  @Override
  public boolean isEnabled() {
    return (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled());
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.cucumber.junit.PickleRunners$PickleRunner";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CucumberUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SkippedByDatadog",
      packageName + ".JUnit4Utils",
      packageName + ".TracingListener",
      packageName + ".CucumberTracingListener",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return CucumberUtils.MuzzleHelper.additionalMuzzleReferences();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("run").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4CucumberSkipInstrumentation.class.getName() + "$CucumberSkipAdvice");
  }

  public static class CucumberSkipAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean run(
        @Advice.FieldValue("pickle") Pickle pickle,
        @Advice.FieldValue("description") Description description,
        @Advice.Argument(0) RunNotifier notifier) {

      TestIdentifier test = CucumberUtils.toTestIdentifier(description);
      SkipReason skipReason =
          TestEventsHandlerHolder.HANDLERS
              .get(TestFrameworkInstrumentation.CUCUMBER)
              .skipReason(test);
      if (skipReason == null) {
        return null;
      }

      if (skipReason == SkipReason.ITR) {
        List<String> tags = pickle.getTags();
        for (String tag : tags) {
          if (tag.endsWith(CIConstants.Tags.ITR_UNSKIPPABLE_TAG)) {
            return null;
          }
        }
      }

      notifier.fireTestAssumptionFailed(
          new Failure(description, new AssumptionViolatedException(skipReason.getDescription())));
      return Boolean.FALSE;
    }
  }
}
