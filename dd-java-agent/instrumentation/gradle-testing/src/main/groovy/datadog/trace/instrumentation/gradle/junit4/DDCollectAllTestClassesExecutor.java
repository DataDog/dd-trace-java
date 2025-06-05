package datadog.trace.instrumentation.gradle.junit4;

import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.instrumentation.junit4.order.JUnit4FailFastClassOrderer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.gradle.api.Action;
import org.gradle.internal.UncheckedException;

/**
 * Based on JUnitPlatformTestClassProcessor {@link
 * org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestClassProcessor}. Collects
 * all test classes to order them before execution.
 */
public class DDCollectAllTestClassesExecutor implements Action<String> {
  private final List<Class<?>> testClasses = new ArrayList<>();
  private final Action<String> delegate;
  private final ClassLoader classLoader;

  public DDCollectAllTestClassesExecutor(Action<String> delegate, ClassLoader junitClassLoader) {
    this.delegate = delegate;
    this.classLoader = junitClassLoader;
  }

  @Override
  public void execute(@Nonnull String testClassName) {
    Class<?> clazz = loadClass(testClassName);

    TestFrameworkInstrumentation framework = JUnit4Utils.classToFramework(clazz);
    if (framework == TestFrameworkInstrumentation.JUNIT4) {
      TestEventsHandlerHolder.start(
          TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.capabilities(true));
    }

    testClasses.add(clazz);
  }

  public void processAllTestClasses() {
    testClasses.sort(
        new JUnit4FailFastClassOrderer(
            TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT4)));

    for (Class<?> clazz : testClasses) {
      delegate.execute(clazz.getName());
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
