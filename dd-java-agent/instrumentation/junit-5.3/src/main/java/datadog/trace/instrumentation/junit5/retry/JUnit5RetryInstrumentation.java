package datadog.trace.instrumentation.junit5.retry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService;

@AutoService(Instrumenter.class)
public class JUnit5RetryInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5RetryInstrumentation() {
    super("ci-visibility", "junit-5", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityFlakyRetryEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.junit.platform.engine.support.hierarchical.NodeTestTask";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestTaskHandle",
      packageName + ".TestDescriptorHandle",
      packageName + ".RetryContext",
      packageName + ".NoOpRetryContext",
      packageName + ".RetryContextImpl",
      packageName + ".RetryContextFactory",
      parentPackageName + ".JUnitPlatformUtils",
      parentPackageName + ".TestIdentifierFactory",
      parentPackageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService$TestTask",
        packageName + ".RetryContext");
  }

  @Override
  public ReferenceProvider runtimeMuzzleReferences() {
    return new TestTaskHandle.MuzzleHelper();
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    List<Reference> additionalReferences = new ArrayList<>();
    additionalReferences.addAll(TestDescriptorHandle.MuzzleHelper.compileReferences());
    additionalReferences.addAll(TestTaskHandle.MuzzleHelper.compileReferences());
    additionalReferences.add(
        new Reference.Builder("org.junit.platform.engine.support.hierarchical.NodeTestTask")
            .withMethod(new String[0], 0, "prepare", "V")
            .withMethod(new String[0], 0, "execute", "V")
            .build());
    return additionalReferences.toArray(new Reference[0]);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("prepare").and(takesNoArguments()),
        JUnit5RetryInstrumentation.class.getName() + "$PrepareRetryContext");
    transformer.applyAdvice(
        named("execute").and(takesNoArguments()),
        JUnit5RetryInstrumentation.class.getName() + "$RetryIfNeeded");
  }

  public static class PrepareRetryContext {
    @Advice.OnMethodEnter
    public static void prepare(@Advice.This HierarchicalTestExecutorService.TestTask testTask) {
      InstrumentationContext.get(HierarchicalTestExecutorService.TestTask.class, RetryContext.class)
          .computeIfAbsent(testTask, RetryContextFactory.INSTANCE)
          .prepareRetry();
    }
  }

  public static class RetryIfNeeded {
    @Advice.OnMethodExit
    public static void execute(@Advice.This HierarchicalTestExecutorService.TestTask testTask) {
      InstrumentationContext.get(HierarchicalTestExecutorService.TestTask.class, RetryContext.class)
          .get(testTask)
          .executeRetryIfNeeded();
    }
  }
}
