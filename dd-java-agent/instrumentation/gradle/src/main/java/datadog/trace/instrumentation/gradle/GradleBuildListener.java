package datadog.trace.instrumentation.gradle;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.util.Collection;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.TaskState;
import org.gradle.process.JavaForkOptions;

// FIXME add a separate switch for Gradle instrumentation (for projects that, like spring-boot, run
// Gradle internally)
// FIXME add integration tests (for inspiration see spring-boot:
// org.springframework.boot.gradle.tasks.run.BootRunIntegrationTests and others in the same module)
public class GradleBuildListener extends BuildAdapter {

  private final BuildEventsHandler<Gradle> buildEventsHandler =
      InstrumentationBridge.getBuildEventsHandler();

  @Override
  public void settingsEvaluated(Settings settings) {
    Gradle gradle = settings.getGradle();
    Path projectRoot = settings.getRootDir().toPath();
    AbstractTestDecorator gradleDecorator = new GradleDecorator(projectRoot);
    ProjectDescriptor rootProject = settings.getRootProject();
    String projectName = rootProject.getName();
    String startCommand = GradleUtils.recreateStartCommand(settings.getStartParameter());
    String gradleVersion = gradle.getGradleVersion();
    buildEventsHandler.onTestSessionStart(
        gradle, gradleDecorator, projectName, startCommand, "gradle", gradleVersion);
  }

  @Override
  public void projectsEvaluated(Gradle gradle) {
    Project rootProject = gradle.getRootProject();
    Collection<GradleUtils.TestFramework> testFrameworks =
        GradleUtils.collectTestFrameworks(rootProject);
    if (testFrameworks.size() == 1) {
      // if the project uses multiple test frameworks, we do not set the tags
      GradleUtils.TestFramework testFramework = testFrameworks.iterator().next();
      buildEventsHandler.onTestFrameworkDetected(gradle, testFramework.name, testFramework.version);
    }

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
      if (!GradleUtils.isTestTask(task)) {
        return;
      }

      Project project = task.getProject();
      Gradle gradle = project.getGradle();
      String projectName = project.getName();
      BuildEventsHandler.ModuleAndSessionId moduleAndSessionId =
          buildEventsHandler.onTestModuleStart(gradle, projectName);

      Collection<GradleUtils.TestFramework> testFrameworks =
          GradleUtils.collectTestFrameworks(project);
      if (testFrameworks.size() == 1) {
        // if the module uses multiple test frameworks, we do not set the tags
        GradleUtils.TestFramework testFramework = testFrameworks.iterator().next();
        buildEventsHandler.onModuleTestFrameworkDetected(
            gradle, projectName, testFramework.name, testFramework.version);
      }

      JavaForkOptions taskForkOptions = (JavaForkOptions) task;
      taskForkOptions.jvmArgs(
          arg(CiVisibilityConfig.CIVISIBILITY_SESSION_ID, moduleAndSessionId.sessionId),
          arg(CiVisibilityConfig.CIVISIBILITY_MODULE_ID, moduleAndSessionId.moduleId));
    }

    private String arg(String propertyName, Object value) {
      return "-D" + Strings.propertyNameToSystemPropertyName(propertyName) + "=" + value;
    }

    @Override
    public void afterExecute(Task task, TaskState state) {
      if (!GradleUtils.isTestTask(task)) {
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
  }
}
