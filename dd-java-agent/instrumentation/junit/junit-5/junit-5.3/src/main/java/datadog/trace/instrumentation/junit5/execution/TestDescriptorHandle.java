package datadog.trace.instrumentation.junit5.execution;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

public class TestDescriptorHandle {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final MethodHandle UNIQUE_ID_SETTER =
      METHOD_HANDLES.privateFieldSetter(AbstractTestDescriptor.class, "uniqueId");

  public static final class MuzzleHelper {
    public static Collection<? extends Reference> compileReferences() {
      return Collections.singletonList(
          new Reference.Builder(AbstractTestDescriptor.class.getName())
              .withField(new String[0], 0, "uniqueId", "Lorg/junit/platform/engine/UniqueId;")
              .build());
    }
  }

  private final TestDescriptor testDescriptor;

  public TestDescriptorHandle(TestDescriptor testDescriptor) {
    /*
     * We're cloning the descriptor to preserve its original state:
     * JUnit will modify some of its fields during and after test execution
     * (one example is parameterized test descriptor,
     * whose invocation context is overwritten with null).
     * Cloning has to be done before each test retry to
     * compensate for the state modifications.
     */
    this.testDescriptor = cloneIfNeeded(testDescriptor);
  }

  public TestDescriptor withIdSuffix(Map<String, Object> suffices) {
    UniqueId updatedId = testDescriptor.getUniqueId();
    for (Map.Entry<String, Object> e : suffices.entrySet()) {
      updatedId = updatedId.append(e.getKey(), String.valueOf(e.getValue()));
    }

    TestDescriptor descriptorClone = cloneIfNeeded(testDescriptor);
    METHOD_HANDLES.invoke(UNIQUE_ID_SETTER, descriptorClone, updatedId);
    return descriptorClone;
  }

  private TestDescriptor cloneIfNeeded(TestDescriptor original) {
    Class<?> clazz = original.getClass();
    String name = clazz.getName();

    if (name.endsWith(".TestMethodTestDescriptor")) {
      // No need to clone for TestMethodTestDescriptor because no states are modified
      return original;
    }

    if (name.endsWith(".TestTemplateInvocationTestDescriptor")) {
      return copyConstructor(
          original,
          clazz,
          "uniqueId",
          "testClass",
          "testMethod",
          "invocationContext",
          "index",
          "configuration");
    }

    if (name.endsWith(".DynamicTestTestDescriptor")) {
      return copyConstructor(
          original, clazz, "uniqueId", "index", "dynamicTest", "source", "configuration");
    }

    throw new IllegalStateException("Unexpected class of TestDescriptor: " + name);
  }

  private TestDescriptor copyConstructor(TestDescriptor original, Class<?> clazz, String... names) {
    try {
      Map<String, Object> fields = getFields(clazz, original);
      for (Constructor<?> ctr : clazz.getDeclaredConstructors()) {
        Object[] args = match(ctr, fields, original, names);
        if (args != null) {
          ctr.setAccessible(true);
          return (TestDescriptor) ctr.newInstance(args);
        }
      }
      throw new IllegalStateException(
          "Failed to find appropriate constructor to clone: " + clazz.getName());
    } catch (Throwable e) {
      throw new IllegalStateException("Failed to clone via constructor", e);
    }
  }

  private Map<String, Object> getFields(Class<?> clazz, Object original)
      throws IllegalAccessException {
    Map<String, Object> fields = new HashMap<>();
    while (clazz != null && clazz != Object.class) {
      for (Field field : clazz.getDeclaredFields()) {
        field.setAccessible(true);
        Object value = field.get(original);

        // Constructor may need `testClass` and `testMethod` parameters, but they are wrapped by
        // `methodInfo`.
        // Expand `methodInfo` to be used in constructor later.
        if (field.getName().equals("methodInfo")) {
          Map<String, Object> methodInfoFields = getFields(field.getType(), value);
          fields.putAll(methodInfoFields);
        }

        fields.put(field.getName(), value);
      }
      clazz = clazz.getSuperclass();
    }
    return fields;
  }

  private Object[] match(
      Constructor<?> ctr, Map<String, Object> fields, Object original, String... names)
      throws IllegalAccessException {
    int cnt = ctr.getParameterCount();

    List<Object> args = new ArrayList<>();

    for (String name : names) {
      for (Map.Entry<String, Object> field : fields.entrySet()) {
        String k = field.getKey();
        Object v = field.getValue();

        if (k.equals(name)) {
          args.add(v);
          break;
        }
      }
    }

    return args.size() == cnt ? args.toArray() : null;
  }
}
