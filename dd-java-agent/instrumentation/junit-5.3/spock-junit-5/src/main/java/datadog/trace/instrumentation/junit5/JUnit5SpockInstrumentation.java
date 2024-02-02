package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;
import org.spockframework.runtime.SpockEngine;

@AutoService(Instrumenter.class)
public class JUnit5SpockInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public JUnit5SpockInstrumentation() {
    super("ci-visibility", "junit-5", "junit-5-spock");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.spockframework.runtime.SpockEngine");
  }

  @Override
  public String instrumentedType() {
    return "org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnitPlatformUtils",
      packageName + ".TestIdentifierFactory",
      packageName + ".SpockUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SpockTracingListener",
      packageName + ".CompositeEngineListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute").and(takesArgument(0, named("org.junit.platform.engine.ExecutionRequest"))),
        JUnit5SpockInstrumentation.class.getName() + "$SpockAdvice");
  }

  @SuppressFBWarnings(
      value = "UC_USELESS_OBJECT",
      justification = "executionRequest is the argument of the original method")
  public static class SpockAdvice {

    @Advice.OnMethodEnter
    public static void addTracingListener(
        @Advice.This TestEngine testEngine,
        @Advice.Argument(value = 0, readOnly = false) ExecutionRequest executionRequest) {
      if (!(testEngine instanceof SpockEngine)) {
        // wrong test engine
        return;
      }

      if (JUnitPlatformUtils.isTestInProgress()) {
        // a test case that is in progress starts a new JUnit instance.
        // It might be done in order to achieve classloader isolation
        // (for example, spring-boot uses this technique).
        // We are already tracking the active test case,
        // and do not want to report the "embedded" JUnit execution
        // as a separate module
        return;
      }

      SpockTracingListener tracingListener = new SpockTracingListener(testEngine);
      EngineExecutionListener originalListener = executionRequest.getEngineExecutionListener();
      EngineExecutionListener compositeListener =
          new CompositeEngineListener(tracingListener, originalListener);
      executionRequest =
          new ExecutionRequest(
              executionRequest.getRootTestDescriptor(),
              compositeListener,
              executionRequest.getConfigurationParameters());
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
