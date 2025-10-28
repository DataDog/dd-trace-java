package datadog.trace.instrumentation.gradle;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.JavaAgent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.project.taskfactory.TaskIdentity;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.PluginManager;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildServiceRegistry;
import org.gradle.api.tasks.TaskState;
import org.gradle.api.tasks.testing.Test;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.execution.taskgraph.TaskListenerInternal;
import org.gradle.internal.InternalBuildListener;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.NestedBuildState;
import org.gradle.internal.build.RootBuildState;
import org.gradle.internal.service.scopes.ListenerService;
import org.gradle.process.CommandLineArgumentProvider;

@ListenerService
public class CiVisibilityGradleListener extends BuildAdapter
    implements InternalBuildListener, TaskListenerInternal {

  private static final String TRACER_VERSION;

  static {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                ClassLoader.getSystemResourceAsStream("dd-java-agent.version")))) {
      TRACER_VERSION = reader.lines().collect(Collectors.joining());
    } catch (IOException e) {
      throw new RuntimeException("Could not read tracer version from dd-java-agent.version", e);
    }
  }

  {
    /*
     * Gradle's configuration cache has a set of "inputs" that determine whether an existing entry
     * can be reused, or a new one should be created.
     * Presence or absence of a javaagent and the variables that are used for the tracer configuration are
     * not among those inputs.
     * That means that a cache entry created without the tracer can be reused when the tracer is present:
     * as the result test tasks will not be instrumented.
     * Or the other way around, a cache entry created with the tracer can be reused when the tracer is absent:
     * as the result a test task may try to access CI Visibility services and fail.
     * Likewise, a cache entry created with an older version of the tracer can be reused with the newer version
     * of the tracer, which is undesirable if there are some changes in the tasks configuration logic.
     * To prevent this we have to ensure that the presence or absence of tracer, as well as the version of the tracer,
     * is a part of the configuration cache inputs.
     * We do this by settings the system property below, since all properties with the prefix "org.gradle.project."
     * are included into configuration cache inputs by default.
     *
     * Please note that this has to be done for every build, so this block has to be dynamic and not static.
     */
    System.setProperty("org.gradle.project.datadog.tracer.version", TRACER_VERSION);
  }

  private final Config config = Config.get();
  private final Gradle gradle;
  private final CiVisibilityService ciVisibilityService;

  public CiVisibilityGradleListener(
      Gradle gradle,
      BuildState buildState,
      BuildEventsListenerRegistry buildEventsListenerRegistry) {
    this.gradle = gradle;

    BuildServiceRegistry sharedServices = gradle.getSharedServices();
    Provider<CiVisibilityService> ciVisibilityServiceProvider =
        sharedServices.registerIfAbsent(
            "ciVisibilityService", CiVisibilityService.class, spec -> {});
    // registration is needed to keep the service alive until the end of the build
    buildEventsListenerRegistry.onTaskCompletion(ciVisibilityServiceProvider);
    ciVisibilityService = ciVisibilityServiceProvider.get();

    String buildPath = buildState.getBuildIdentifier().getBuildPath();
    Path projectRoot = buildState.getBuildRootDir().toPath();

    boolean nestedBuild;
    String nestedBuildPath;
    if (buildState instanceof NestedBuildState) {
      nestedBuild = true;
      nestedBuildPath = buildPath;
    } else {
      nestedBuild = false;
      nestedBuildPath = null;
    }

    StartParameterInternal startParameter = getStartParameter(buildState);
    String startCommand = recreateStartCommand(startParameter, nestedBuildPath);
    String gradleVersion = gradle.getGradleVersion();
    ciVisibilityService.onBuildStart(
        buildPath, projectRoot, startCommand, gradleVersion, nestedBuild);
  }

  private static StartParameterInternal getStartParameter(BuildState buildState) {
    if (buildState instanceof RootBuildState) {
      RootBuildState rootBuildState = (RootBuildState) buildState;
      return rootBuildState.getStartParameter();
    } else if (buildState instanceof NestedBuildState) {
      NestedBuildState nestedBuildState = (NestedBuildState) buildState;
      BuildDefinition buildDefinition = nestedBuildState.getBuildDefinition();
      return buildDefinition.getStartParameter();
    } else {
      throw new IllegalArgumentException("Unexpected build state: " + buildState);
    }
  }

  /**
   * Returns command line used to start the build. We instrument Gradle daemon process, not the
   * client process that is launched from the command line, so the result of this method is an
   * approximation of what the actual command could look like
   */
  private static String recreateStartCommand(StartParameter startParameter, String buildPath) {
    StringBuilder command = new StringBuilder("gradle");

    if (buildPath != null && !buildPath.isEmpty()) {
      command.append(' ').append(buildPath);
    }

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

    return command.toString();
  }

  @Override
  public void projectsEvaluated(Gradle gradle) {
    if (!config.isCiVisibilityAutoConfigurationEnabled()) {
      return;
    }

    Project rootProject = gradle.getRootProject();
    Set<Project> projects = rootProject.getAllprojects();
    for (Project project : projects) {
      PluginManager pluginManager = project.getPluginManager();
      pluginManager.apply(CiVisibilityPlugin.class);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void beforeExecute(TaskIdentity<?> taskIdentity) {
    String taskPath = taskIdentity.getTaskPath();
    if (!Test.class.isAssignableFrom(taskIdentity.getTaskType())) {
      ciVisibilityService.onBuildTaskStart(taskPath);
      return;
    }

    String projectPath = taskIdentity.getProjectPath();
    Project project = gradle.getRootProject().project(projectPath);
    Test task = (Test) project.getTasks().getByName(taskIdentity.name);

    Map<String, Object> inputProperties = task.getInputs().getProperties();
    BuildModuleLayout moduleLayout =
        (BuildModuleLayout) inputProperties.get(CiVisibilityPluginExtension.MODULE_LAYOUT_PROPERTY);
    if ((moduleLayout == null || moduleLayout.getSourceSets().isEmpty())
        && project.getExtensions().findByName("android") != null) {
      moduleLayout = AndroidGradleUtils.getAndroidModuleLayout(project, task);
    }

    JavaAgent jacocoAgent = getJacocoAgent(task);

    Path jvmExecutable = CiVisibilityPluginExtension.getEffectiveExecutable(task);
    List<Path> taskClasspath = CiVisibilityPluginExtension.getClasspath(task);

    ciVisibilityService.onModuleStart(
        taskPath, moduleLayout, jvmExecutable, taskClasspath, jacocoAgent);
  }

  private JavaAgent getJacocoAgent(Test task) {
    for (CommandLineArgumentProvider jvmArgumentProvider : task.getJvmArgumentProviders()) {
      if (!(jvmArgumentProvider instanceof Named)) {
        continue;
      }
      Named namedProvider = (Named) jvmArgumentProvider;
      String providerName = namedProvider.getName();
      if (!providerName.toLowerCase().contains("jacoco")) {
        continue;
      }
      Iterable<String> arguments = jvmArgumentProvider.asArguments();
      for (String arg : arguments) {
        if (!arg.contains("-javaagent:")) {
          continue;
        }
        String argNoPrefix = arg.substring(arg.indexOf(':') + 1);
        int agentArgsIndex = argNoPrefix.indexOf('=');
        if (agentArgsIndex < 0) {
          return new JavaAgent(argNoPrefix, null);
        } else {
          String agentPath = argNoPrefix.substring(0, agentArgsIndex);
          String args = argNoPrefix.substring(argNoPrefix.indexOf('=') + 1);
          return new JavaAgent(agentPath, args);
        }
      }
    }
    return null;
  }

  @Override
  public void afterExecute(TaskIdentity<?> taskIdentity, TaskState state) {
    String taskPath = taskIdentity.getTaskPath();
    Throwable failure = state.getFailure();

    if (!Test.class.isAssignableFrom(taskIdentity.getTaskType())) {
      ciVisibilityService.onBuildTaskFinish(taskPath, failure);
      return;
    }

    String reason = state.getSkipped() || !state.getDidWork() ? state.getSkipMessage() : null;
    ciVisibilityService.onModuleFinish(taskPath, failure, reason);
  }

  @Override
  public void buildFinished(BuildResult result) {
    ciVisibilityService.onBuildFinish(result.getFailure());
  }
}
