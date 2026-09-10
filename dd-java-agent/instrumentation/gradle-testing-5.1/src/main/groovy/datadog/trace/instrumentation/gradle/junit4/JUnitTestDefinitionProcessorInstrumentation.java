package datadog.trace.instrumentation.gradle.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.instrumentation.junit4.JUnit4Instrumentation;
import net.bytebuddy.asm.Advice;
import org.gradle.api.internal.tasks.testing.ClassTestDefinition;
import org.gradle.api.internal.tasks.testing.TestDefinitionConsumer;

@AutoService(InstrumenterModule.class)
public class JUnitTestDefinitionProcessorInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JUnitTestDefinitionProcessorInstrumentation() {
    super("ci-visibility", "gradle", "junit4");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().getCiVisibilityTestOrder() != null;
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.api.internal.tasks.testing.junit.JUnitTestDefinitionProcessor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      JUnit4Instrumentation.class.getPackage().getName() + ".JUnit4Utils",
      JUnit4Instrumentation.class.getPackage().getName() + ".TestEventsHandlerHolder",
      JUnit4Instrumentation.class.getPackage().getName() + ".SkippedByDatadog",
      JUnit4Instrumentation.class.getPackage().getName() + ".TracingListener",
      JUnit4Instrumentation.class.getPackage().getName() + ".order.JUnit4FailFastClassOrderer",
      packageName + ".DDCollectAllTestDefinitionsExecutor",
    };
  }

  @Override
  public String muzzleDirective() {
    return "skipMuzzle";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("createTestExecutor")
            .and(takesArgument(0, named("org.gradle.internal.actor.Actor")))
            .and(returns(named("org.gradle.api.internal.tasks.testing.TestDefinitionConsumer"))),
        JUnitTestDefinitionProcessorInstrumentation.class.getName() + "$TestExecutorAdvice");
  }

  public static class TestExecutorAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodExit
    public static void onTestExecutorCreation(
        @Advice.Return(readOnly = false) TestDefinitionConsumer<ClassTestDefinition> executor) {
      String testOrder = Config.get().getCiVisibilityTestOrder();
      if (!CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(testOrder)) {
        throw new IllegalArgumentException("Unknown test order: " + testOrder);
      }

      executor =
          new DDCollectAllTestDefinitionsExecutor(
              executor, Thread.currentThread().getContextClassLoader());
    }
  }
}
