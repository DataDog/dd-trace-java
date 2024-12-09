package datadog.trace.instrumentation.junit5.order;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.engine.config.JupiterConfiguration;

@AutoService(InstrumenterModule.class)
public class JUnit5TestOrderInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5TestOrderInstrumentation() {
    super("ci-visibility", "junit-5", "test-order");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.jupiter.engine.config.JupiterConfiguration";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".TestEventsHandlerHolder",
      parentPackageName + ".JUnitPlatformUtils",
      packageName + ".JUnit5OrderUtils",
      packageName + ".FailFastClassOrderer",
      packageName + ".FailFastMethodOrderer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getDefaultTestClassOrderer"),
        JUnit5TestOrderInstrumentation.class.getName() + "$ClassOrdererAdvice");
    transformer.applyAdvice(
        named("getDefaultTestMethodOrderer"),
        JUnit5TestOrderInstrumentation.class.getName() + "$MethodOrdererAdvice");
  }

  public static class ClassOrdererAdvice {
    @Advice.OnMethodEnter
    public static void onGetClassOrdererEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(JupiterConfiguration.class);
    }

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "classOrderer is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void onGetClassOrdererExit(
        @Advice.Return(readOnly = false) Optional<ClassOrderer> classOrderer) {
      if (CallDepthThreadLocalMap.decrementCallDepth(JupiterConfiguration.class) != 0) {
        // nested call
        return;
      }
      String testOrder = Config.get().getCiVisibilityTestOrder();
      if (CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder)) {
        classOrderer =
            Optional.of(
                new FailFastClassOrderer(
                    TestEventsHandlerHolder.TEST_EVENTS_HANDLER, classOrderer.orElse(null)));
      } else {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }
    }
  }

  public static class MethodOrdererAdvice {
    @Advice.OnMethodEnter
    public static void onGetMethodOrdererEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(JupiterConfiguration.class);
    }

    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "methodOrderer is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void onGetMethodOrdererExit(
        @Advice.Return(readOnly = false) Optional<MethodOrderer> methodOrderer) {
      if (CallDepthThreadLocalMap.decrementCallDepth(JupiterConfiguration.class) != 0) {
        // nested call
        return;
      }
      String testOrder = Config.get().getCiVisibilityTestOrder();
      if (CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder)) {
        methodOrderer =
            Optional.of(
                new FailFastMethodOrderer(
                    TestEventsHandlerHolder.TEST_EVENTS_HANDLER, methodOrderer.orElse(null)));
      } else {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }
    }
  }
}
