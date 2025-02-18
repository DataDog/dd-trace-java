package datadog.trace.instrumentation.junit5.execution;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
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
import org.junit.platform.engine.support.hierarchical.Node;
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
  private static final MethodHandle TEST_DESCRIPTOR_SETTER =
      METHOD_HANDLES.privateFieldSetter(TEST_TASK_CLASS, "testDescriptor");

  private static final MethodHandle NODE_SETTER =
      METHOD_HANDLES.privateFieldSetter(TEST_TASK_CLASS, "node");

  private static final MethodHandle PARENT_CONTEXT_GETTER =
      METHOD_HANDLES.privateFieldGetter(TEST_TASK_CLASS, "parentContext");
  private static final MethodHandle PARENT_CONTEXT_SETTER =
      METHOD_HANDLES.privateFieldSetter(TEST_TASK_CLASS, "parentContext");

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

  public void setTestDescriptor(TestDescriptor testDescriptor) {
    METHOD_HANDLES.invoke(TEST_DESCRIPTOR_SETTER, testTask, testDescriptor);
  }

  public void setNode(Node<?> node) {
    METHOD_HANDLES.invoke(NODE_SETTER, testTask, node);
  }

  public EngineExecutionContext getParentContext() {
    return METHOD_HANDLES.invoke(PARENT_CONTEXT_GETTER, testTask);
  }

  public void setParentContext(EngineExecutionContext parentContext) {
    METHOD_HANDLES.invoke(PARENT_CONTEXT_SETTER, testTask, parentContext);
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
