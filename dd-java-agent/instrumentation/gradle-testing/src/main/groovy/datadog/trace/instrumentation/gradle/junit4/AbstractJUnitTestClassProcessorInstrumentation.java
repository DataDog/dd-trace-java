package datadog.trace.instrumentation.gradle.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.instrumentation.junit4.JUnit4Instrumentation;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.gradle.api.Action;
import org.gradle.internal.actor.Actor;

@AutoService(InstrumenterModule.class)
public class AbstractJUnitTestClassProcessorInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public AbstractJUnitTestClassProcessorInstrumentation() {
    super("ci-visibility", "gradle", "junit4");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.api.internal.tasks.testing.junit.AbstractJUnitTestClassProcessor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      JUnit4Instrumentation.class.getPackage().getName() + ".SkippedByDatadog",
      JUnit4Instrumentation.class.getPackage().getName() + ".JUnit4Utils",
      JUnit4Instrumentation.class.getPackage().getName() + ".TestEventsHandlerHolder",
      JUnit4Instrumentation.class.getPackage().getName() + ".TracingListener",
      JUnit4Instrumentation.class.getPackage().getName() + ".order.JUnit4FailFastClassOrderer",
      packageName + ".DDCollectAllTestClassesExecutor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("stop"),
        AbstractJUnitTestClassProcessorInstrumentation.class.getName()
            + "$ProcessAllTestClassesAdvice");
  }

  @Override
  public String muzzleDirective() {
    return "skipMuzzle";
  }

  public static class ProcessAllTestClassesAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodEnter
    public static void onStop(
        @Advice.FieldValue(value = "executor") final Action<String> executor,
        @Advice.FieldValue(value = "resultProcessorActor") final Actor resultProcessorActor) {
      String testOrder = Config.get().getCiVisibilityTestOrder();
      if (!CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder)) {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }

      if (executor instanceof DDCollectAllTestClassesExecutor) {
        ((DDCollectAllTestClassesExecutor) executor).processAllTestClasses();
      }
    }
  }
}
