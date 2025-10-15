package datadog.trace.instrumentation.junit5.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;

@AutoService(InstrumenterModule.class)
public class JUnit5ExecutionStoreInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5ExecutionStoreInstrumentation() {
    super("ci-visibility", "junit-5", "test-retry");
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
      parentPackageName + ".JUnitPlatformUtils", parentPackageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.platform.engine.TestDescriptor",
        "datadog.trace.api.civisibility.execution.TestExecutionHistory");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("discover")
            .and(
                takesArgument(0, named("org.junit.platform.engine.EngineDiscoveryRequest"))
                    .and(takesArgument(1, named("org.junit.platform.engine.UniqueId")))),
        JUnit5ExecutionStoreInstrumentation.class.getName() + "$ContextStoreAdvice");
  }

  public static class ContextStoreAdvice {
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "executionRequest is the argument of the original method")
    @Advice.OnMethodEnter
    public static void setContextStores() {
      ContextStore<TestDescriptor, TestExecutionHistory> contextStore =
          InstrumentationContext.get(TestDescriptor.class, TestExecutionHistory.class);
      TestEventsHandlerHolder.setExecutionHistoryStore(contextStore);
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
