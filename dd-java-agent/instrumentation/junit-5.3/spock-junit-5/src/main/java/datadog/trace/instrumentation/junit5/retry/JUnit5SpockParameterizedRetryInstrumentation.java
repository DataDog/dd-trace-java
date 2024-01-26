package datadog.trace.instrumentation.junit5.retry;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
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
@AutoService(Instrumenter.class)
public class JUnit5SpockParameterizedRetryInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public JUnit5SpockParameterizedRetryInstrumentation() {
    super("ci-visibility", "junit-5", "junit-5-spock", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityFlakyRetryEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.spockframework.runtime.ParameterizedFeatureChildExecutor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpockParameterizedExecutionListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        JUnit5SpockParameterizedRetryInstrumentation.class.getName()
            + "$SpockParameterizedRetryAdvice");
  }

  public static class SpockParameterizedRetryAdvice {

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
