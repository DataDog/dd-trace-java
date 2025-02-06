package datadog.trace.instrumentation.junit5.execution;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;

/**
 * Applies a patch to Spock's parameterized tests executor, needed to support retries for
 * parameterized tests
 */
@AutoService(InstrumenterModule.class)
public class JUnit5SpockParameterizedExecutionInstrumentation
    extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5SpockParameterizedExecutionInstrumentation() {
    super("ci-visibility", "junit-5", "junit-5-spock", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.spockframework.runtime.ParameterizedFeatureChildExecutor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".JUnitPlatformUtils",
      packageName + ".SpockParameterizedExecutionListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        JUnit5SpockParameterizedExecutionInstrumentation.class.getName()
            + "$SpockParameterizedExecutionAdvice");
  }

  public static class SpockParameterizedExecutionAdvice {

    @SuppressWarnings("bytebuddy-exception-suppression")
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "executionListener is a field in the instrumented class")
    @Advice.OnMethodExit
    public static void afterConstructor(
        @Advice.FieldValue(value = "executionListener", readOnly = false)
            EngineExecutionListener executionListener,
        @Advice.FieldValue("pending") Map<TestDescriptor, CompletableFuture<?>> pending) {
      executionListener = new SpockParameterizedExecutionListener(executionListener, pending);
    }
  }
}
