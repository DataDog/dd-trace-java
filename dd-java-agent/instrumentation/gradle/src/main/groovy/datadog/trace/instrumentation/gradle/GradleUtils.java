package datadog.trace.instrumentation.gradle;

import java.util.Map;
import org.gradle.StartParameter;
import org.gradle.api.Task;
import org.gradle.process.JavaForkOptions;

public abstract class GradleUtils {

  private static final String TEST_TASK_CLASS_NAME = "org.gradle.api.tasks.testing.Test";

  public static boolean isTestTask(Task task) {
    if (!(task instanceof JavaForkOptions)) {
      return false;
    }
    Class<?> taskClass = task.getClass();
    while (taskClass != null) {
      // class name can contain suffix "_Decorated"
      if (taskClass.getName().startsWith(TEST_TASK_CLASS_NAME)) {
        return true;
      }
      taskClass = taskClass.getSuperclass();
    }
    return false;
  }

  /**
   * Returns command line used to start the build. We instrument Gradle daemon process, not the
   * client process that is launched from the command line, so the result of this method is an
   * approximation of what the actual command could look like
   */
  public static String recreateStartCommand(StartParameter startParameter) {
    StringBuilder command = new StringBuilder("gradle");

    for (String taskName : startParameter.getTaskNames()) {
      command.append(' ').append(taskName);
    }

    for (String excludedTaskName : startParameter.getExcludedTaskNames()) {
      command.append(" -x").append(excludedTaskName);
    }

    for (Map.Entry<String, String> e : startParameter.getProjectProperties().entrySet()) {
      String propertyKey = e.getKey();
      String propertyValue = e.getValue();
      command.append(" -P").append(propertyKey);
      if (propertyValue != null && !propertyValue.isEmpty()) {
        command.append('=').append(propertyValue);
      }
    }

    for (Map.Entry<String, String> e : startParameter.getSystemPropertiesArgs().entrySet()) {
      String propertyKey = e.getKey();
      String propertyValue = e.getValue();
      command.append(" -D").append(propertyKey);
      if (propertyValue != null && !propertyValue.isEmpty()) {
        command.append('=').append(propertyValue);
      }
    }

    return command.toString();
  }
}
