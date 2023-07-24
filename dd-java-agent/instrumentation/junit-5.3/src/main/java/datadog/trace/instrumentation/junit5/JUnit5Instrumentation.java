package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.core.LauncherConfig;

@AutoService(Instrumenter.class)
public class JUnit5Instrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit5Instrumentation() {
    super("junit", "junit-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.platform.launcher.core.LauncherConfig";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnitPlatformUtils",
      packageName + ".JUnitPlatformUtils$Cucumber",
      packageName + ".JUnitPlatformUtils$Spock",
      packageName + ".JUnitPlatformLauncherUtils",
      packageName + ".JUnitPlatformLauncherUtils$Cucumber",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".TracingListener",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getAdditionalTestExecutionListeners").and(takesNoArguments()),
        JUnit5Instrumentation.class.getName() + "$JUnit5Advice");
  }

  public static class JUnit5Advice {

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "listeners is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void addTracingListener(
        @Advice.This LauncherConfig config,
        @Advice.Return(readOnly = false) Collection<TestExecutionListener> listeners) {

      if (JUnitPlatformUtils.isTestInProgress()) {
        // a test case that is in progress starts a new JUnit instance.
        // It might be done in order to achieve classloader isolation
        // (for example, spring-boot uses this technique).
        // We are already tracking the active test case,
        // and do not want to report the "embedded" JUnit execution
        // as a separate module
        return;
      }

      Collection<TestEngine> testEngines = JUnitPlatformLauncherUtils.getTestEngines(config);
      final TracingListener listener = new TracingListener(testEngines);

      Collection<TestExecutionListener> modifiedListeners = new ArrayList<>(listeners);
      modifiedListeners.add(listener);

      listeners = modifiedListeners;
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
