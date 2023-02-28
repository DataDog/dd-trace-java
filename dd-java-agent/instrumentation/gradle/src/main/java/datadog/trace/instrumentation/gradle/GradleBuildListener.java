package datadog.trace.instrumentation.gradle;

import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.civisibility.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.civisibility.TestContext;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.TaskState;
import org.gradle.process.JavaForkOptions;

public class GradleBuildListener extends BuildAdapter {

  private static final String TEST_TASK_CLASS_NAME = "org.gradle.api.tasks.testing.Test";

  private final BuildEventsHandler<Gradle> buildEventsHandler = new BuildEventsHandler<>();

  @Override
  public void settingsEvaluated(Settings settings) {
    Gradle gradle = settings.getGradle();
    Path projectRoot = settings.getRootDir().toPath();
    TestDecorator gradleDecorator = new GradleDecorator(projectRoot);
    ProjectDescriptor rootProject = settings.getRootProject();
    String projectName = rootProject.getName();
    String startCommand = recreateStartCommand(settings.getStartParameter());
    buildEventsHandler.onTestSessionStart(gradle, gradleDecorator, projectName, startCommand);
  }

  /**
   * Returns command line used to start the build. We instrument Gradle daemon process, not the
   * client process that is launched from the command line, so the result of this method is an
   * approximation of what the actual command could look like
   */
  private String recreateStartCommand(StartParameter startParameter) {
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

  @Override
  public void projectsEvaluated(Gradle gradle) {
    Project rootProject = gradle.getRootProject();
    Collection<TestFramework> testFrameworks = collectTestFrameworks(rootProject);
    if (testFrameworks.size() == 1) {
      // if the project uses multiple test frameworks, we do not set the tags
      TestFramework testFramework = testFrameworks.iterator().next();
      buildEventsHandler.onTestFrameworkDetected(gradle, testFramework.name, testFramework.version);
    }

    gradle.addListener(new TestTaskExecutionListener(buildEventsHandler));
  }

  private static Collection<TestFramework> collectTestFrameworks(Project project) {
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

  @Override
  public void buildFinished(BuildResult result) {
    Gradle gradle = result.getGradle();
    buildEventsHandler.onTestSessionFinish(gradle);
  }

  static final class TestFramework {
    private final String name;
    private final String version;

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
      return name.equals(that.name) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, version);
    }
  }

  static final class TestTaskExecutionListener implements TaskExecutionListener {

    private final BuildEventsHandler<Gradle> buildEventsHandler;

    TestTaskExecutionListener(BuildEventsHandler<Gradle> buildEventsHandler) {
      this.buildEventsHandler = buildEventsHandler;
    }

    @Override
    public void beforeExecute(Task task) {
      if (!isTestTask(task)) {
        return;
      }

      Project project = task.getProject();
      Gradle gradle = project.getGradle();
      String projectName = project.getName();
      TestContext testModuleContext = buildEventsHandler.onTestModuleStart(gradle, projectName);

      Collection<TestFramework> testFrameworks = collectTestFrameworks(project);
      if (testFrameworks.size() == 1) {
        // if the module uses multiple test frameworks, we do not set the tags
        TestFramework testFramework = testFrameworks.iterator().next();
        buildEventsHandler.onModuleTestFrameworkDetected(
            gradle, projectName, testFramework.name, testFramework.version);
      }

      long moduleId = testModuleContext.getId();
      long sessionId = testModuleContext.getParentId();

      JavaForkOptions taskForkOptions = (JavaForkOptions) task;
      taskForkOptions.jvmArgs(
          arg(CiVisibilityConfig.CIVISIBILITY_SESSION_ID, sessionId),
          arg(CiVisibilityConfig.CIVISIBILITY_MODULE_ID, moduleId));
    }

    private String arg(String propertyName, Object value) {
      return "-D" + Strings.propertyNameToSystemPropertyName(propertyName) + "=" + value;
    }

    @Override
    public void afterExecute(Task task, TaskState state) {
      if (!isTestTask(task)) {
        return;
      }

      Project project = task.getProject();
      Gradle gradle = project.getGradle();
      String projectName = project.getName();

      Throwable failure = state.getFailure();
      if (failure != null) {
        buildEventsHandler.onTestModuleFail(gradle, projectName, failure);

      } else if (state.getSkipped() || !state.getDidWork()) {
        String reason = state.getSkipMessage();
        buildEventsHandler.onTestModuleSkip(gradle, projectName, reason);
      }

      buildEventsHandler.onTestModuleFinish(gradle, projectName);
    }

    // TODO figure out why Test class cannot be used
    private static boolean isTestTask(Task task) {
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
  }
}
