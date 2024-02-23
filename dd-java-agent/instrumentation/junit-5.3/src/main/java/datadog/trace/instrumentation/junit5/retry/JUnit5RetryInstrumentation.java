package datadog.trace.instrumentation.junit5.retry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder;
import datadog.trace.instrumentation.junit5.TestIdentifierFactory;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

@AutoService(Instrumenter.class)
public class JUnit5RetryInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5RetryInstrumentation() {
    super("ci-visibility", "junit-5", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityTestRetryEnabled();
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
      parentPackageName + ".TestIdentifierFactory",
      parentPackageName + ".TestEventsHandlerHolder",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.platform.engine.TestDescriptor",
        "datadog.trace.api.civisibility.retry.TestRetryPolicy");
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
        isTypeInitializer(), JUnit5RetryInstrumentation.class.getName() + "$BeforeTypeInit");
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(
                    3,
                    named(
                        "org.junit.platform.engine.support.hierarchical.ThrowableCollector.Factory"))),
        JUnit5RetryInstrumentation.class.getName() + "$BeforeTaskConstructor");
    transformer.applyAdvice(
        named("execute").and(takesNoArguments()),
        JUnit5RetryInstrumentation.class.getName() + "$RetryIfNeeded");
  }

  public static class BeforeTaskConstructor {
    @Advice.OnMethodEnter
    public static void replaceThrowableCollectorFactory(
        @Advice.Argument(value = 3, readOnly = false)
            ThrowableCollector.Factory throwableCollectorFactory) {
      throwableCollectorFactory = new ThrowableCollectorFactoryWrapper(throwableCollectorFactory);
    }
  }

  public static class RetryIfNeeded {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean execute(@Advice.This HierarchicalTestExecutorService.TestTask testTask) {
      if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
        // is possible when running tracer's instrumentation tests
        return null;
      }

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

      TestIdentifier testIdentifier = TestIdentifierFactory.createTestIdentifier(testDescriptor);
      TestRetryPolicy retryPolicy =
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.retryPolicy(testIdentifier);
      if (!retryPolicy.retriesLeft()) {
        return null;
      }

      ThrowableCollectorFactoryWrapper factory =
          (ThrowableCollectorFactoryWrapper) taskHandle.getThrowableCollectorFactory();
      EngineExecutionContext parentContext = taskHandle.getParentContext();
      TestDescriptorHandle descriptorHandle = new TestDescriptorHandle(testDescriptor);

      int retryAttemptIdx = 0;
      boolean retry;
      while (true) {
        factory.setSuppressFailures(retryPolicy.retriesLeft() && retryPolicy.suppressFailures());

        long startTimestamp = System.currentTimeMillis();
        CallDepthThreadLocalMap.incrementCallDepth(HierarchicalTestExecutorService.TestTask.class);
        testTask.execute();
        CallDepthThreadLocalMap.decrementCallDepth(HierarchicalTestExecutorService.TestTask.class);
        long duration = System.currentTimeMillis() - startTimestamp;

        Throwable error = factory.getCollector().getThrowable();
        factory.setSuppressFailures(false); // restore default behavior

        boolean success = error == null || JUnitPlatformUtils.isAssumptionFailure(error);
        retry = retryPolicy.retry(success, duration);
        if (!retry) {
          break;
        }

        /*
         * Some event listeners (notably the one used by Gradle)
         * require every test execution to have a distinct unique ID.
         * Rerunning a test with the ID that was executed previously will cause errors.
         */
        TestDescriptor retryDescriptor =
            descriptorHandle.withIdSuffix(
                JUnitPlatformUtils.RETRY_DESCRIPTOR_ID_SUFFIX, String.valueOf(++retryAttemptIdx));
        taskHandle.setTestDescriptor(retryDescriptor);
        taskHandle.setNode((Node<?>) retryDescriptor);
        taskHandle.getListener().dynamicTestRegistered(retryDescriptor);
        InstrumentationContext.get(TestDescriptor.class, TestRetryPolicy.class)
            .put(retryDescriptor, retryPolicy);

        // restore parent context, since the reference is overwritten with null after execution
        taskHandle.setParentContext(parentContext);
      }
      return Boolean.TRUE; // skip original method execution
    }
  }
}
