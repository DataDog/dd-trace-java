package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;

@AutoService(Instrumenter.class)
public class JUnit5TestTemplateTestDescriptorInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public JUnit5TestTemplateTestDescriptorInstrumentation() {
    super("junit", "junit-5", "junit-5-test-template-test-descriptor");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    Boolean itrKillswitch = Config.get().getCiVisibilityItrEnabled();
    return super.isApplicable(enabledSystems) && (itrKillswitch == null || itrKillswitch);
  }

  @Override
  public String instrumentedType() {
    return "org.junit.jupiter.engine.descriptor.TestTemplateTestDescriptor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ItrUtils", packageName + ".TestFrameworkUtils", packageName + ".ItrPredicate",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("createInvocationTestDescriptor"),
        JUnit5TestTemplateTestDescriptorInstrumentation.class.getName()
            + "$JUnit5TestTemplateTestDescriptorAdvice");
  }

  private static class JUnit5TestTemplateTestDescriptorAdvice {

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "descriptor is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void filter(
        @Advice.Return(readOnly = false) Optional<TestDescriptor> descriptor) {
      if (descriptor != null && descriptor.isPresent()) {
        if (!ItrPredicate.INSTANCE.test(descriptor.get())) {
          descriptor = Optional.empty();
        }
      }
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
