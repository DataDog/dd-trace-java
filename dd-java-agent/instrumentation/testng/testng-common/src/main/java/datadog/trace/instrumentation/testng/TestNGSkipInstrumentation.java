package datadog.trace.instrumentation.testng;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import java.lang.reflect.Method;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;

@AutoService(InstrumenterModule.class)
public class TestNGSkipInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public TestNGSkipInstrumentation() {
    super("testng", "testng-itr");
  }

  @Override
  public boolean isEnabled() {
    return (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled());
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.testng.internal.MethodInvocationHelper",
      "org.testng.internal.invokers.MethodInvocationHelper"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("invokeMethod")
            .and(takesArguments(3))
            .and(takesArgument(0, Method.class))
            .and(takesArgument(1, Object.class))
            .and(takesArgument(2, Object[].class)),
        TestNGSkipInstrumentation.class.getName() + "$InvokeMethodAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestNGUtils",
      packageName + ".TestNGClassListener",
      packageName + ".TestEventsHandlerHolder",
    };
  }

  public static class InvokeMethodAdvice {
    @Advice.OnMethodEnter
    public static void invokeMethod(
        @Advice.Argument(0) final Method method,
        @Advice.Argument(1) final Object instance,
        @Advice.Argument(2) final Object[] parameters) {
      TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(method, instance, parameters);
      SkipReason skipReason =
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skipReason(testIdentifier);
      if (skipReason == null) {
        return;
      }

      if (skipReason == SkipReason.ITR) {
        List<String> groups = TestNGUtils.getGroups(method);
        if (groups.contains(CIConstants.Tags.ITR_UNSKIPPABLE_TAG)) {
          return;
        }
      }

      throw new SkipException(skipReason.getDescription());
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(final DataProvider dataProvider) {
      dataProvider.name();
    }
  }
}
