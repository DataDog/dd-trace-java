package datadog.trace.instrumentation.junit5.order;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import java.util.Optional;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.engine.config.JupiterConfiguration;

@AutoService(InstrumenterModule.class)
public class JUnit5TestOrderInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5TestOrderInstrumentation() {
    super("ci-visibility", "junit-5", "test-order");
  }

  @Override
  public boolean isEnabled() {
    return Config.get().getCiVisibilityTestOrder() != null;
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
      parentPackageName + ".JUnitPlatformUtils",
      parentPackageName + ".TestEventsHandlerHolder",
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
                    TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT5),
                    classOrderer.orElse(null)));
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
                    TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT5),
                    methodOrderer.orElse(null)));
      } else {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }
    }
  }
}
