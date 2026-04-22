package datadog.trace.instrumentation.testng7.order;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import datadog.trace.instrumentation.testng.TestNGInstrumentation;
import datadog.trace.instrumentation.testng.TestNGUtils;
import datadog.trace.util.Strings;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import org.testng.IMethodInterceptor;
import org.testng.annotations.CustomAttribute;

@AutoService(InstrumenterModule.class)
public class TestNGOrderInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String parentPackageName = Strings.getPackageName(TestNGUtils.class.getName());

  public TestNGOrderInstrumentation() {
    super("ci-visibility", "testng", "test-order");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String instrumentedType() {
    return "org.testng.TestNG";
  }

  @Override
  public int order() {
    // Depends on datadog.trace.instrumentation.testng.TestNGInstrumentation,
    // as it needs datadog.trace.instrumentation.testng.TestEventsHandlerHolder.start to be called;
    // The bytecode insertion order is inverse:
    // for lower order values the bytecode instructions are executed later
    // (at least for @Advice.OnMethodExit methods)
    return TestNGInstrumentation.ORDER - 1;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        MethodDescription::isConstructor,
        TestNGOrderInstrumentation.class.getName() + "$InsertInterceptorAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".TestNGClassListener",
      parentPackageName + ".TestNGUtils",
      parentPackageName + ".TestEventsHandlerHolder",
      packageName + ".FailFastOrderInterceptor",
    };
  }

  public static class InsertInterceptorAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodExit
    public static void prependFailFastInterceptor(
        @Advice.FieldValue("m_methodInterceptors") List<IMethodInterceptor> methodInterceptors) {
      String testOrder = Config.get().getCiVisibilityTestOrder();
      if (CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder)) {
        for (IMethodInterceptor methodInterceptor : methodInterceptors) {
          if (methodInterceptor instanceof FailFastOrderInterceptor) {
            return;
          }
        }

        // adding our interceptor as the first one:
        // that way custom interceptors added by the users will have higher priority
        methodInterceptors.add(
            0, new FailFastOrderInterceptor(TestEventsHandlerHolder.TEST_EVENTS_HANDLER));

      } else {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }
    }

    // TestNG 7.0 and above
    public static String muzzleCheck(final CustomAttribute customAttribute) {
      return customAttribute.name();
    }
  }
}
