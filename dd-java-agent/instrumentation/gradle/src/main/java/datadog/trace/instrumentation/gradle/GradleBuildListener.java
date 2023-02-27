package datadog.trace.instrumentation.gradle;

import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.civisibility.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.civisibility.TestContext;
import datadog.trace.util.Strings;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.TaskState;
import org.gradle.process.JavaForkOptions;

public class GradleBuildListener extends BuildAdapter {

  private static final String TEST_TASK_CLASS_NAME = "org.gradle.api.tasks.testing.Test";

  private final BuildEventsHandler<Gradle> buildEventsHandler =
      new BuildEventsHandler<>(GradleDecorator.DECORATE);

  @Override
  public void settingsEvaluated(Settings settings) {
    Gradle gradle = settings.getGradle();
    buildEventsHandler.onTestSessionStart(gradle);
  }

  @Override
  public void projectsEvaluated(Gradle gradle) {
    gradle.addListener(new TestTaskExecutionListener(buildEventsHandler));
  }

  @Override
  public void buildFinished(BuildResult result) {
    Gradle gradle = result.getGradle();
    buildEventsHandler.onTestSessionFinish(gradle);
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
