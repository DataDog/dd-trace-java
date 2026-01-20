package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;
import org.spockframework.runtime.SpecNode;
import org.spockframework.runtime.SpockNode;

@AutoService(InstrumenterModule.class)
public class JUnit5SpockSkipInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JUnit5SpockSkipInstrumentation() {
    super("ci-visibility", "junit-5", "junit-5-spock");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.spockframework.runtime.SpockEngine");
  }

  @Override
  public boolean isEnabled() {
    return (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled());
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
      packageName + ".TestDataFactory",
      packageName + ".SpockUtils",
      packageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("shouldBeSkipped").and(takesArguments(1)),
        JUnit5SpockSkipInstrumentation.class.getName() + "$JUnit5SkipAdvice");
  }

  /**
   * !!!!!!!!!!!!!!!! IMPORTANT !!!!!!!!!!!!!!!! Do not use or refer to any classes from {@code
   * org.junit.platform.launcher} package in here: in some Gradle projects this package is not
   * available in CL where this instrumentation is injected
   */
  public static class JUnit5SkipAdvice {

    @Advice.OnMethodEnter
    public static void beforeSkipCheck() {
      CallDepthThreadLocalMap.incrementCallDepth(SpockNode.class);
    }

    @SuppressWarnings("bytebuddy-exception-suppression")
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

      TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler =
          TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.SPOCK);

      if (testEventsHandler == null) {
        // should only happen in integration tests
        // because we cannot avoid instrumenting ourselves
        return;
      }

      if (spockNode instanceof SpecNode) {
        // suite
        SpecNode specNode = (SpecNode) spockNode;
        Set<? extends TestDescriptor> features = specNode.getChildren();

        SkipReason suiteSkipReason = null;
        for (TestDescriptor feature : features) {
          if (feature instanceof SpockNode && SpockUtils.isItrUnskippable((SpockNode<?>) feature)) {
            return;
          }

          TestIdentifier featureIdentifier = SpockUtils.toTestIdentifier(feature);
          if (featureIdentifier == null) {
            return;
          }
          SkipReason skipReason = testEventsHandler.skipReason(featureIdentifier);
          if (skipReason == null) {
            return;
          }
          if (suiteSkipReason != null && suiteSkipReason != skipReason) {
            // we cannot have a mix of skip reasons in the suite for technical reasons
            return;
          }
          suiteSkipReason = skipReason;
        }

        if (suiteSkipReason == null) {
          return;
        }
        if (suiteSkipReason == SkipReason.ITR && SpockUtils.isItrUnskippable(specNode)) {
          return;
        }
        skipResult = Node.SkipResult.skip(suiteSkipReason.getDescription());

      } else {
        // individual test case
        TestIdentifier test = SpockUtils.toTestIdentifier(spockNode);
        if (test == null) {
          return;
        }
        SkipReason skipReason = testEventsHandler.skipReason(test);
        if (skipReason == null) {
          return;
        }
        if (skipReason == SkipReason.ITR && SpockUtils.isItrUnskippable(spockNode)) {
          return;
        }
        skipResult = Node.SkipResult.skip(skipReason.getDescription());
      }
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
