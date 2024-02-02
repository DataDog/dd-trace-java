package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cucumber.core.gherkin.Pickle;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.AssumptionViolatedException;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

@AutoService(Instrumenter.class)
public class JUnit4CucumberItrInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit4CucumberItrInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-cucumber");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityItrEnabled();
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
      packageName + ".SkippedByItr",
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
        JUnit4CucumberItrInstrumentation.class.getName() + "$CucumberItrAdvice");
  }

  public static class CucumberItrAdvice {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean run(
        @Advice.FieldValue("pickle") Pickle pickle,
        @Advice.FieldValue("description") Description description,
        @Advice.Argument(0) RunNotifier notifier) {

      List<String> tags = pickle.getTags();
      for (String tag : tags) {
        if (tag.endsWith(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)) {
          return null;
        }
      }

      TestIdentifier test = CucumberUtils.toTestIdentifier(description);
      if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skip(test)) {
        notifier.fireTestAssumptionFailed(
            new Failure(
                description,
                new AssumptionViolatedException(InstrumentationBridge.ITR_SKIP_REASON)));
        return Boolean.FALSE;
      } else {
        return null;
      }
    }
  }
}
