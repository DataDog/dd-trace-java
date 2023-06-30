package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;

@AutoService(Instrumenter.class)
public class JUnit5DiscoveryRequestInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit5DiscoveryRequestInstrumentation() {
    super("junit", "junit-5", "junit-5-discovery-request");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityItrEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.platform.launcher.LauncherDiscoveryRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ItrUtils",
      packageName + ".TestFrameworkUtils",
      packageName + ".ItrPredicate",
      packageName + ".ItrPostDiscoveryFilter",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getPostDiscoveryFilters").and(takesNoArguments()),
        JUnit5DiscoveryRequestInstrumentation.class.getName() + "$JUnit5DiscoveryRequestAdvice");
  }

  private static class JUnit5DiscoveryRequestAdvice {

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "filters is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void addItrPostDiscoveryFilter(
        @Advice.This LauncherDiscoveryRequest request,
        @Advice.Return(readOnly = false) List<PostDiscoveryFilter> filters) {

      final PostDiscoveryFilter itrFilter = new ItrPostDiscoveryFilter();

      List<PostDiscoveryFilter> modifiedFilters = new ArrayList<>(filters);
      modifiedFilters.add(itrFilter);

      filters = modifiedFilters;
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
