package datadog.trace.instrumentation.junit5.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.instrumentation.junit5.TestDataFactory;
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

@AutoService(InstrumenterModule.class)
public class JUnit5ExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5ExecutionInstrumentation() {
    super("ci-visibility", "junit-5", "test-retry");
  }

  @Override
  public boolean isEnabled() {
    return Config.get().isCiVisibilityExecutionPoliciesEnabled();
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
      packageName + ".ThrowableCollectorFactoryWrapper",
      parentPackageName + ".JUnitPlatformUtils",
      parentPackageName + ".TestDataFactory",
      parentPackageName + ".TestEventsHandlerHolder",
    };
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
        isConstructor()
            .and(
                takesArgument(
                    3,
                    named(
                        "org.junit.platform.engine.support.hierarchical.ThrowableCollector.Factory"))),
        JUnit5ExecutionInstrumentation.class.getName() + "$BeforeTaskConstructor");
    transformer.applyAdvice(
        named("execute").and(takesNoArguments()),
        JUnit5ExecutionInstrumentation.class.getName() + "$ExecutionAdvice");
  }

  public static class BeforeTaskConstructor {
    @Advice.OnMethodEnter
    public static void replaceThrowableCollectorFactory(
        @Advice.Argument(value = 3, readOnly = false)
            ThrowableCollector.Factory throwableCollectorFactory) {
      throwableCollectorFactory = new ThrowableCollectorFactoryWrapper(throwableCollectorFactory);
    }
  }

  public static class ExecutionAdvice {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean execute(@Advice.This HierarchicalTestExecutorService.TestTask testTask) {

      if (CallDepthThreadLocalMap.getCallDepth(HierarchicalTestExecutorService.TestTask.class)
          != 0) {
        // nested call
        return null;
      }

      TestTaskHandle taskHandle = new TestTaskHandle(testTask);
      TestDescriptor testDescriptor = taskHandle.getTestDescriptor();
      if (testDescriptor.isContainer()) {
        // Parameterized tests have a container descriptor for the test case as a whole,
        // which contains a child descriptor for each set of parameters.
        // Below we are creating a TestIdentifier without the parameters,
        // so the identifier created from the container descriptor would be exactly the same
        // as the identifier created from any of the children.
        // Therefore, we need to filter out the container descriptors here.
        return null;
      }

      if (!TestDataFactory.shouldBeTraced(testDescriptor)) {
        return null;
      }

      String engineId = JUnitPlatformUtils.getEngineId(testDescriptor);
      TestFrameworkInstrumentation framework = JUnitPlatformUtils.engineIdToFramework(engineId);

      if (TestEventsHandlerHolder.HANDLERS.get(framework) == null) {
        // is possible when running tracer's instrumentation tests
        return null;
      }

      TestIdentifier testIdentifier = TestDataFactory.createTestIdentifier(testDescriptor);
      TestSourceData testSource = TestDataFactory.createTestSourceData(testDescriptor);
      Collection<String> testTags = JUnitPlatformUtils.getTags(testDescriptor);
      TestExecutionPolicy executionPolicy =
          TestEventsHandlerHolder.HANDLERS
              .get(framework)
              .executionPolicy(testIdentifier, testSource, testTags);
      if (!executionPolicy.applicable()) {
        return null;
      }

      TestEventsHandlerHolder.setExecutionHistory(testDescriptor, executionPolicy);

      ThrowableCollectorFactoryWrapper factory =
          (ThrowableCollectorFactoryWrapper) taskHandle.getThrowableCollectorFactory();
      EngineExecutionContext parentContext = taskHandle.getParentContext();
      TestDescriptorHandle descriptorHandle = new TestDescriptorHandle(testDescriptor);

      int retryAttemptIdx = 0;
      while (true) {
        factory.setSuppressFailures(executionPolicy.suppressFailures());

        CallDepthThreadLocalMap.incrementCallDepth(HierarchicalTestExecutorService.TestTask.class);
        testTask.execute();
        CallDepthThreadLocalMap.decrementCallDepth(HierarchicalTestExecutorService.TestTask.class);

        factory.setSuppressFailures(false); // restore default behavior

        if (!executionPolicy.applicable()) {
          break;
        }

        /*
         * Some event listeners (notably the one used by Gradle)
         * require every test execution to have a distinct unique ID.
         * Rerunning a test with the ID that was executed previously will cause errors.
         */
        Map<String, Object> suffix =
            Collections.singletonMap(
                JUnitPlatformUtils.RETRY_DESCRIPTOR_ID_SUFFIX, String.valueOf(++retryAttemptIdx));

        TestDescriptor retryDescriptor = descriptorHandle.withIdSuffix(suffix);
        taskHandle.setTestDescriptor(retryDescriptor);
        taskHandle.setNode((Node<?>) retryDescriptor);
        taskHandle.getListener().dynamicTestRegistered(retryDescriptor);
        TestEventsHandlerHolder.setExecutionHistory(retryDescriptor, executionPolicy);

        // restore parent context, since the reference is overwritten with null after execution
        taskHandle.setParentContext(parentContext);
      }
      return Boolean.TRUE; // skip original method execution
    }
  }
}
