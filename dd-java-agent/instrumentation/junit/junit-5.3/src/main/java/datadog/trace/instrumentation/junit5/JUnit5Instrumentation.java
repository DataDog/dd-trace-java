package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;

@AutoService(InstrumenterModule.class)
public class JUnit5Instrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

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
      packageName + ".ExecutionRequestFactory",
      packageName + ".TestDataFactory",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".TracingListener",
      packageName + ".CompositeEngineListener",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.junit.platform.engine.TestDescriptor", "java.lang.Object");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("discover")
            .and(
                takesArgument(0, named("org.junit.platform.engine.EngineDiscoveryRequest"))
                    .and(takesArgument(1, named("org.junit.platform.engine.UniqueId")))),
        JUnit5Instrumentation.class.getName() + "$ContextStoreAdvice");
    transformer.applyAdvice(
        named("execute").and(takesArgument(0, named("org.junit.platform.engine.ExecutionRequest"))),
        JUnit5Instrumentation.class.getName() + "$JUnit5Advice");
  }

  public static class ContextStoreAdvice {
    @Advice.OnMethodEnter
    public static void setContextStores(@Advice.This TestEngine testEngine) {
      ContextStore<TestDescriptor, Object> contextStore =
          InstrumentationContext.get(TestDescriptor.class, Object.class);
      TestEventsHandlerHolder.start(
          testEngine, (ContextStore) contextStore, (ContextStore) contextStore);
    }
  }

  public static class JUnit5Advice {
    @Advice.OnMethodEnter
    public static void addTracingListener(
        @Advice.This TestEngine testEngine,
        @Advice.Argument(value = 0, readOnly = false) ExecutionRequest executionRequest) {
      if (JUnitPlatformUtils.engineToFramework(testEngine) != TestFrameworkInstrumentation.JUNIT5) {
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
          ExecutionRequestFactory.createExecutionRequest(executionRequest, compositeListener);
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
