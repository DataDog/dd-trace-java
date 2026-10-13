package datadog.trace.instrumentation.junit5.execution;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import net.bytebuddy.pool.TypePool;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

public class TestTaskHandle {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final String TEST_TASK_CLASS =
      "org.junit.platform.engine.support.hierarchical.NodeTestTask";
  private static final String TEST_TASK_CONTEXT_CLASS =
      "org.junit.platform.engine.support.hierarchical.NodeTestTaskContext";

  private static final MethodHandle TEST_DESCRIPTOR_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CLASS, "testDescriptor");

  private static final MethodHandle PARENT_CONTEXT_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CLASS, "parentContext");
  private static final MethodHandle PARENT_CONTEXT_SETTER =
      METHOD_HANDLES.privateFieldSetter(TEST_TASK_CLASS, "parentContext");

  /** NodeTestTask's {@code (NodeTestTaskContext, TestDescriptor)} constructor (1.3.1+) */
  private static final Class<?> TEST_TASK_CONTEXT_CLASS_REF =
      JUnitPlatformUtils.loadClass(TEST_TASK_CONTEXT_CLASS);

  private static final MethodHandle TEST_TASK_CONSTRUCTOR =
      TEST_TASK_CONTEXT_CLASS_REF != null
          ? METHOD_HANDLES.constructor(
              TEST_TASK_CLASS, TEST_TASK_CONTEXT_CLASS_REF, TestDescriptor.class)
          : null;

  // Legacy fallback setters, lazily created to avoid JEP 500 warnings, only used on 1.3.0
  private static volatile MethodHandle testDescriptorSetter;
  private static volatile MethodHandle nodeSetter;

  private static MethodHandle testDescriptorSetter() {
    MethodHandle handle = testDescriptorSetter;
    if (handle == null) {
      handle = METHOD_HANDLES.privateFieldSetter(TEST_TASK_CLASS, "testDescriptor");
      testDescriptorSetter = handle;
    }
    return handle;
  }

  private static MethodHandle nodeSetter() {
    MethodHandle handle = nodeSetter;
    if (handle == null) {
      handle = METHOD_HANDLES.privateFieldSetter(TEST_TASK_CLASS, "node");
      nodeSetter = handle;
    }
    return handle;
  }

  private static final MethodHandle THROWABLE_COLLECTOR_FACTORY_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CLASS, "throwableCollectorFactory");
  private static final MethodHandle TASK_CONTEXT_THROWABLE_COLLECTOR_FACTORY_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CONTEXT_CLASS, "throwableCollectorFactory");

  private static final MethodHandle EXECUTION_LISTENER_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CLASS, "listener");
  private static final MethodHandle TASK_CONTEXT_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CLASS, "taskContext");
  private static final MethodHandle TASK_CONTEXT_EXECUTION_LISTENER_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CONTEXT_CLASS, "listener");

  public static final class MuzzleHelper implements ReferenceProvider {
    @Override
    public Iterable<Reference> buildReferences(TypePool typePool) {
      if (typePool.describe(TEST_TASK_CONTEXT_CLASS).isResolved()) {
        // junit-platform-engine 1.3.1+
        return Arrays.asList(
            new Reference.Builder(TEST_TASK_CLASS)
                .withField(
                    new String[0],
                    0,
                    "taskContext",
                    'L' + TEST_TASK_CONTEXT_CLASS.replace('.', '/') + ';')
                .build(),
            new Reference.Builder(TEST_TASK_CONTEXT_CLASS)
                .withField(
                    new String[0],
                    0,
                    "listener",
                    "Lorg/junit/platform/engine/EngineExecutionListener;")
                .build());
      } else {
        // junit-platform-engine 1.3.0 and earlier
        return Collections.singletonList(
            new Reference.Builder(TEST_TASK_CLASS)
                .withField(
                    new String[0],
                    0,
                    "listener",
                    "Lorg/junit/platform/engine/EngineExecutionListener;")
                .build());
      }
    }

    public static Collection<? extends Reference> compileReferences() {
      return Collections.singletonList(
          new Reference.Builder(TEST_TASK_CLASS)
              .withField(
                  new String[0], 0, "testDescriptor", "Lorg/junit/platform/engine/TestDescriptor;")
              .withField(
                  new String[0], 0, "node", "Lorg/junit/platform/engine/support/hierarchical/Node;")
              .withField(
                  new String[0],
                  0,
                  "parentContext",
                  "Lorg/junit/platform/engine/support/hierarchical/EngineExecutionContext;")
              .withField(
                  new String[0],
                  0,
                  "throwableCollector",
                  "Lorg/junit/platform/engine/support/hierarchical/ThrowableCollector;")
              .build());
    }
  }

  private final HierarchicalTestExecutorService.TestTask testTask;
  private final Object /* org.junit.platform.engine.support.hierarchical.NodeTestTaskContext */
      testTaskContext;

  public TestTaskHandle(HierarchicalTestExecutorService.TestTask testTask) {
    this.testTask = testTask;
    this.testTaskContext = METHOD_HANDLES.invoke(TASK_CONTEXT_GETTER, testTask);
  }

  public TestDescriptor getTestDescriptor() {
    return METHOD_HANDLES.invoke(TEST_DESCRIPTOR_GETTER, testTask);
  }

  public EngineExecutionContext getParentContext() {
    return METHOD_HANDLES.invoke(PARENT_CONTEXT_GETTER, testTask);
  }

  /**
   * Returns a task that will execute the given retry descriptor. If possible, a brand-new
   * NodeTestTask is constructed; otherwise we fall back to overwriting the current task's fields
   * (non-compliant with JEP500).
   */
  public HierarchicalTestExecutorService.TestTask createRetryTask(
      TestDescriptor descriptor, EngineExecutionContext parentContext) {
    if (TEST_TASK_CONSTRUCTOR != null && testTaskContext != null) {
      Object retryTask = METHOD_HANDLES.invoke(TEST_TASK_CONSTRUCTOR, testTaskContext, descriptor);
      if (retryTask != null) {
        METHOD_HANDLES.invoke(PARENT_CONTEXT_SETTER, retryTask, parentContext);
        return (HierarchicalTestExecutorService.TestTask) retryTask;
      }
    }
    // fallback (< 1.3.1): reuse the current task by overwriting its final fields.
    METHOD_HANDLES.invoke(testDescriptorSetter(), testTask, descriptor);
    METHOD_HANDLES.invoke(nodeSetter(), testTask, descriptor);
    METHOD_HANDLES.invoke(PARENT_CONTEXT_SETTER, testTask, parentContext);
    return testTask;
  }

  public EngineExecutionListener getListener() {
    return testTaskContext != null
        ? METHOD_HANDLES.invoke(TASK_CONTEXT_EXECUTION_LISTENER_GETTER, testTaskContext)
        : METHOD_HANDLES.invoke(EXECUTION_LISTENER_GETTER, testTask);
  }

  public ThrowableCollector.Factory getThrowableCollectorFactory() {
    return testTaskContext != null
        ? METHOD_HANDLES.invoke(TASK_CONTEXT_THROWABLE_COLLECTOR_FACTORY_GETTER, testTaskContext)
        : METHOD_HANDLES.invoke(THROWABLE_COLLECTOR_FACTORY_GETTER, testTask);
  }

  public void execute() {
    testTask.execute();
  }
}
