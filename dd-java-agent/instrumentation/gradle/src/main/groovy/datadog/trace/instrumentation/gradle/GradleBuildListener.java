package datadog.trace.instrumentation.gradle;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GradleBuildListener extends BuildAdapter {

  private static final Logger log = LoggerFactory.getLogger(GradleBuildListener.class);

  private final BuildEventsHandler<Gradle> buildEventsHandler =
      InstrumentationBridge.createBuildEventsHandler();

  @Override
  public void settingsEvaluated(Settings settings) {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }
    Gradle gradle = settings.getGradle();
    Path projectRoot = settings.getRootDir().toPath();
    ProjectDescriptor rootProject = settings.getRootProject();
    String projectName = rootProject.getName();
    String startCommand = GradleUtils.recreateStartCommand(settings.getStartParameter());
    String gradleVersion = gradle.getGradleVersion();
    buildEventsHandler.onTestSessionStart(
        gradle, projectName, projectRoot, startCommand, "gradle", gradleVersion);
  }

  @Override
  public void projectsEvaluated(Gradle gradle) {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      return;
    }

    Project rootProject = gradle.getRootProject();

    if (config.isCiVisibilityAutoConfigurationEnabled()) {
      Set<Project> projects = rootProject.getAllprojects();
      Map<Path, Collection<Task>> testExecutions = configureProjects(projects);
      configureTestExecutions(gradle, testExecutions);
    }

    Collection<GradleUtils.TestFramework> testFrameworks =
        GradleUtils.collectTestFrameworks(rootProject);
    if (testFrameworks.size() == 1) {
      // if the project uses multiple test frameworks, we do not set the tags
      GradleUtils.TestFramework testFramework = testFrameworks.iterator().next();
      buildEventsHandler.onTestFrameworkDetected(gradle, testFramework.name, testFramework.version);
    } else if (testFrameworks.size() > 1) {
      log.info(
          "Multiple test frameworks detected: {}. Test framework data will not be populated",
          testFrameworks);
    }

    gradle.addListener(new TestTaskExecutionListener(buildEventsHandler));
  }

  private Map<Path, Collection<Task>> configureProjects(Set<Project> projects) {
    Map<Path, Collection<Task>> testExecutions = new HashMap<>();
    for (Project project : projects) {
      try {
        Map<Path, Collection<Task>> projectExecutions =
            GradleProjectConfigurator.INSTANCE.configureProject(project);
        for (Map.Entry<Path, Collection<Task>> e : projectExecutions.entrySet()) {
          Path path = e.getKey();
          Collection<Task> executions = e.getValue();
          testExecutions.computeIfAbsent(path, k -> new ArrayList<>()).addAll(executions);
        }
      } catch (Exception e) {
        log.error("Error while configuring projects", e);
      }
    }
    return testExecutions;
  }

  private void configureTestExecutions(Gradle gradle, Map<Path, Collection<Task>> testExecutions) {
    for (Map.Entry<Path, Collection<Task>> e : testExecutions.entrySet()) {
      try {
        Path jvmExecutablePath = e.getKey();
        Collection<Task> executions = e.getValue();
        configureTestExecutions(gradle, jvmExecutablePath, executions);

      } catch (Exception ex) {
        log.error("Error while configuring test executions", ex);
      }
    }
  }

  private void configureTestExecutions(
      Gradle gradle, Path jvmExecutablePath, Collection<Task> testExecutions) {
    ModuleExecutionSettings moduleExecutionSettings =
        buildEventsHandler.getModuleExecutionSettings(gradle, jvmExecutablePath);

    for (Task testExecution : testExecutions) {
      Path modulePath = testExecution.getProject().getProjectDir().toPath();
      GradleProjectConfigurator.INSTANCE.configureTracer(
          testExecution,
          moduleExecutionSettings.getSystemProperties(),
          moduleExecutionSettings.getSkippableTests(modulePath));
    }
  }

  @Override
  public void buildFinished(BuildResult result) {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }

    Gradle gradle = result.getGradle();
    Throwable failure = result.getFailure();
    if (failure != null) {
      buildEventsHandler.onTestSessionFail(gradle, failure);
    }

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
      String taskPath = task.getPath();
      String startCommand = GradleUtils.recreateStartCommand(gradle.getStartParameter());
      BuildEventsHandler.ModuleAndSessionId moduleAndSessionId =
          buildEventsHandler.onTestModuleStart(gradle, taskPath, startCommand, null);

      Collection<GradleUtils.TestFramework> testFrameworks =
          GradleUtils.collectTestFrameworks(project);
      if (testFrameworks.size() == 1) {
        // if the module uses multiple test frameworks, we do not set the tags
        GradleUtils.TestFramework testFramework = testFrameworks.iterator().next();
        buildEventsHandler.onModuleTestFrameworkDetected(
            gradle, taskPath, testFramework.name, testFramework.version);
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
      String taskPath = task.getPath();

      Throwable failure = state.getFailure();
      if (failure != null) {
        buildEventsHandler.onTestModuleFail(gradle, taskPath, failure);

      } else if (state.getSkipped() || !state.getDidWork()) {
        String reason = state.getSkipMessage();
        buildEventsHandler.onTestModuleSkip(gradle, taskPath, reason);
      }

      buildEventsHandler.onTestModuleFinish(gradle, taskPath);
    }
  }
}
