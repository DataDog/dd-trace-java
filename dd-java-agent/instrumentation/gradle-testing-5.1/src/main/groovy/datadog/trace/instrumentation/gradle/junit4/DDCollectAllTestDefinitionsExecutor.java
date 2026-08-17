package datadog.trace.instrumentation.gradle.junit4;

import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.instrumentation.junit4.order.JUnit4FailFastClassOrderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.internal.tasks.testing.ClassTestDefinition;
import org.gradle.api.internal.tasks.testing.TestDefinitionConsumer;
import org.gradle.internal.UncheckedException;

public class DDCollectAllTestDefinitionsExecutor
    implements TestDefinitionConsumer<ClassTestDefinition> {
  private final List<Class<?>> testClasses = new ArrayList<>();
  private final Map<String, ClassTestDefinition> testDefinitions = new HashMap<>();
  private final TestDefinitionConsumer<ClassTestDefinition> delegate;
  private final ClassLoader classLoader;

  public DDCollectAllTestDefinitionsExecutor(
      TestDefinitionConsumer<ClassTestDefinition> delegate, ClassLoader junitClassLoader) {
    this.delegate = delegate;
    this.classLoader = junitClassLoader;
  }

  @Override
  public void accept(ClassTestDefinition testDefinition) {
    Class<?> clazz = loadClass(testDefinition.getTestClassName());

    TestFrameworkInstrumentation framework = JUnit4Utils.classToFramework(clazz);
    if (framework == TestFrameworkInstrumentation.JUNIT4) {
      TestEventsHandlerHolder.start(
          TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.capabilities(true));
    }

    testClasses.add(clazz);
    testDefinitions.put(testDefinition.getTestClassName(), testDefinition);
  }

  public void processAllTestClasses() {
    testClasses.sort(
        new JUnit4FailFastClassOrderer(
            TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT4)));

    for (Class<?> clazz : testClasses) {
      delegate.accept(testDefinitions.get(clazz.getName()));
    }
  }

  private Class<?> loadClass(String testClassName) {
    try {
      return Class.forName(testClassName, false, classLoader);
    } catch (ClassNotFoundException e) {
      throw UncheckedException.throwAsUncheckedException(e);
    }
  }
}
