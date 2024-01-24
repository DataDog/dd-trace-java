package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;

@AutoService(Instrumenter.class)
public class JUnit5Instrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit5Instrumentation() {
    super("ci-visibility", "junit-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.platform.engine.TestEngine";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // JUnit 4 has a dedicated instrumentation
        .and(not(named("org.junit.vintage.engine.VintageTestEngine")))
        // suites are only used to organize other test engines
        .and(not(named("org.junit.platform.suite.engine.SuiteTestEngine")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnitPlatformUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".TracingListener",
      packageName + ".CompositeEngineListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute").and(takesArgument(0, named("org.junit.platform.engine.ExecutionRequest"))),
        JUnit5Instrumentation.class.getName() + "$JUnit5Advice");
  }

  public static class JUnit5Advice {

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "executionRequest is the argument of the original method")
    @Advice.OnMethodEnter
    public static void addTracingListener(
        @Advice.This TestEngine testEngine,
        @Advice.Argument(value = 0, readOnly = false) ExecutionRequest executionRequest) {
      String testEngineClassName = testEngine.getClass().getName();
      if (testEngineClassName.startsWith("io.cucumber")
          || testEngineClassName.startsWith("org.spockframework")) {
        // Cucumber and Spock have dedicated instrumentations.
        // We can only filter out calls to their engines at runtime,
        // since they do not declare their own "execute" method,
        // but inherit it from their parent class
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

      TracingListener tracingListener = new TracingListener(testEngine);
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
