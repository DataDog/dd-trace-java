package datadog.trace.instrumentation.gradle;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
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

  public static Collection<TestFramework> collectTestFrameworks(Project project) {
    Collection<TestFramework> testFrameworks = new HashSet<>();

    ConfigurationContainer configurations = project.getConfigurations();
    for (Configuration configuration : configurations.getAsMap().values()) {
      for (Dependency dependency : configuration.getAllDependencies()) {
        String group = dependency.getGroup();
        String name = dependency.getName();
        if ("junit".equals(group) && "junit".equals(name)) {
          testFrameworks.add(new TestFramework("junit4", dependency.getVersion()));

        } else if ("org.junit.jupiter".equals(group)) {
          testFrameworks.add(new TestFramework("junit5", dependency.getVersion()));

        } else if ("org.testng".equals(group) && "testng".equals(name)) {
          testFrameworks.add(new TestFramework("testng", dependency.getVersion()));
        }
      }
    }

    for (Project childProject : project.getChildProjects().values()) {
      testFrameworks.addAll(collectTestFrameworks(childProject));
    }
    return testFrameworks;
  }

  public static final class TestFramework {
    public final String name;
    public final String version;

    TestFramework(String name, String version) {
      this.name = name;
      this.version = version;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestFramework that = (TestFramework) o;
      return Objects.equals(name, that.name) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, version);
    }

    @Override
    public String toString() {
      return "TestFramework{" + "name='" + name + '\'' + ", version='" + version + '\'' + '}';
    }
  }
}
