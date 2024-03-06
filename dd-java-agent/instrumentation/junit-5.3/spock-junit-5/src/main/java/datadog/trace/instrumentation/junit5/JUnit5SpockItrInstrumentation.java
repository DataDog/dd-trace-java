package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;
import org.spockframework.runtime.SpecNode;
import org.spockframework.runtime.SpockNode;

@AutoService(Instrumenter.class)
public class JUnit5SpockItrInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit5SpockItrInstrumentation() {
    super("ci-visibility", "junit-5", "junit-5-spock");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.spockframework.runtime.SpockEngine");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityItrEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.spockframework.runtime.SpockNode";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnitPlatformUtils",
      packageName + ".TestIdentifierFactory",
      packageName + ".SpockUtils",
      packageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("shouldBeSkipped").and(takesArguments(1)),
        JUnit5SpockItrInstrumentation.class.getName() + "$JUnit5ItrAdvice");
  }

  /**
   * !!!!!!!!!!!!!!!! IMPORTANT !!!!!!!!!!!!!!!! Do not use or refer to any classes from {@code
   * org.junit.platform.launcher} package in here: in some Gradle projects this package is not
   * available in CL where this instrumentation is injected
   */
  public static class JUnit5ItrAdvice {

    @Advice.OnMethodEnter
    public static void beforeSkipCheck() {
      CallDepthThreadLocalMap.incrementCallDepth(SpockNode.class);
    }

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "skipResult is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void shouldBeSkipped(
        @Advice.This SpockNode<?> spockNode,
        @Advice.Return(readOnly = false) Node.SkipResult skipResult) {
      if (CallDepthThreadLocalMap.decrementCallDepth(SpockNode.class) > 0) {
        // nested call
        return;
      }

      if (skipResult.isSkipped()) {
        return;
      }

      if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
        // should only happen in integration tests
        // because we cannot avoid instrumenting ourselves
        return;
      }

      if (SpockUtils.isUnskippable(spockNode)) {
        return;
      }

      if (spockNode instanceof SpecNode) {
        // suite
        SpecNode specNode = (SpecNode) spockNode;
        Set<? extends TestDescriptor> features = specNode.getChildren();
        for (TestDescriptor feature : features) {
          if (feature instanceof SpockNode && SpockUtils.isUnskippable((SpockNode<?>) feature)) {
            return;
          }

          TestIdentifier featureIdentifier = SpockUtils.toTestIdentifier(feature);
          if (featureIdentifier == null
              || !TestEventsHandlerHolder.TEST_EVENTS_HANDLER.isSkippable(featureIdentifier)) {
            return;
          }
        }

        // all children are skippable
        for (TestDescriptor feature : features) {
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skip(SpockUtils.toTestIdentifier(feature));
        }
        skipResult = Node.SkipResult.skip(InstrumentationBridge.ITR_SKIP_REASON);

      } else {
        // individual test case
        TestIdentifier test = SpockUtils.toTestIdentifier(spockNode);
        if (test != null && TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skip(test)) {
          skipResult = Node.SkipResult.skip(InstrumentationBridge.ITR_SKIP_REASON);
        }
      }
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
