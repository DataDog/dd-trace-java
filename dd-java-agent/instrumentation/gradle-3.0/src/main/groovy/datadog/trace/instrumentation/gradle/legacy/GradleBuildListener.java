package datadog.trace.instrumentation.gradle.legacy;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GradleBuildListener extends BuildAdapter {

  private static final Logger log = LoggerFactory.getLogger(GradleBuildListener.class);

  private final BuildEventsHandler<Gradle> buildEventsHandler =
      InstrumentationBridge.createBuildEventsHandler();

  @Override
  public void settingsEvaluated(@Nonnull Settings settings) {
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
        gradle, projectName, projectRoot, startCommand, "gradle", gradleVersion, null);
  }

  @Override
  public void projectsEvaluated(@Nonnull Gradle gradle) {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      return;
    }

    if (config.isCiVisibilityAutoConfigurationEnabled()) {
      BuildSessionSettings sessionSettings = buildEventsHandler.getSessionSettings(gradle);

      Project rootProject = gradle.getRootProject();
      Set<Project> projects = rootProject.getAllprojects();
      for (Project project : projects) {
        try {
          GradleProjectConfigurator.INSTANCE.configureProject(project, sessionSettings);
        } catch (Exception e) {
          log.error("Error while configuring project {}", project.getName(), e);
        }
      }
    }

    gradle.addListener(new TestTaskExecutionListener(buildEventsHandler));
  }

  @Override
  public void buildFinished(@Nonnull BuildResult result) {
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
    public void beforeExecute(@Nonnull Task task) {
      Project project = task.getProject();
      Gradle gradle = project.getGradle();
      String taskPath = task.getPath();

      if (!GradleUtils.isTestTask(task)) {
        buildEventsHandler.onBuildTaskStart(gradle, taskPath, Collections.emptyMap());
        return;
      }

      List<String> sourceSetNames = Config.get().getCiVisibilityJacocoGradleSourceSets();
      BuildModuleLayout moduleLayout = GradleUtils.getModuleLayout(project, sourceSetNames);
      Path jvmExecutable = GradleUtils.getEffectiveExecutable(task);
      List<Path> classpath = GradleUtils.getClasspath(task);
      JavaAgent jacocoAgent = GradleUtils.getJacocoAgent(task);

      BuildModuleSettings moduleSettings =
          buildEventsHandler.onTestModuleStart(
              gradle, taskPath, moduleLayout, jvmExecutable, classpath, jacocoAgent, null);
      Map<String, String> systemProperties = moduleSettings.getSystemProperties();
      GradleProjectConfigurator.INSTANCE.configureTracer(task, systemProperties);
    }

    @Override
    public void afterExecute(@Nonnull Task task, @Nonnull TaskState state) {
      Project project = task.getProject();
      Gradle gradle = project.getGradle();
      String taskPath = task.getPath();
      Throwable failure = state.getFailure();

      if (!GradleUtils.isTestTask(task)) {
        if (failure != null) {
          buildEventsHandler.onBuildTaskFail(gradle, taskPath, failure);
        }
        buildEventsHandler.onBuildTaskFinish(gradle, taskPath);
        return;
      }

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
