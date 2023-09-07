package datadog.trace.instrumentation.maven3;

import static java.util.stream.Collectors.toList;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.util.AgentThreadFactory;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.LifecycleTask;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenLifecycleParticipant.class);

  private final BuildEventsHandler<MavenExecutionRequest> buildEventsHandler =
      InstrumentationBridge.createBuildEventsHandler();

  @Override
  public void afterSessionStart(MavenSession session) {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }

    ExecutionListener originalExecutionListener = session.getRequest().getExecutionListener();
    ExecutionListener spyExecutionListener = new MavenExecutionListener(buildEventsHandler);

    // We cannot add an ExecutionListener to the request, we can only replace the existing one.
    // Since we want to preserve the original listener, the solution is to use a "splitter",
    // that will forward each event both to the original listener and to our custom one.
    // Since ExecutionListener may (and does) change depending on Maven version,
    // we use a dynamic proxy instead of implementing the interface
    InvocationHandler invocationHandler =
        (Object target, Method method, Object[] args) -> {
          method.invoke(spyExecutionListener, args);
          return method.invoke(originalExecutionListener, args);
        };
    ExecutionListener proxyExecutionListener =
        (ExecutionListener)
            Proxy.newProxyInstance(
                MavenLifecycleParticipant.class.getClassLoader(),
                new Class[] {ExecutionListener.class},
                invocationHandler);
    session.getRequest().setExecutionListener(proxyExecutionListener);
  }

  @Override
  public void afterProjectsRead(MavenSession session) {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      return;
    }

    MavenProject rootProject = session.getTopLevelProject();
    Path projectRoot = rootProject.getBasedir().toPath();

    MavenExecutionRequest request = session.getRequest();
    String projectName = rootProject.getName();
    String startCommand = MavenUtils.getCommandLine(session);
    String mavenVersion = MavenUtils.getMavenVersion(session);
    buildEventsHandler.onTestSessionStart(
        request, projectName, projectRoot, startCommand, "maven", mavenVersion);

    if (!config.isCiVisibilityAutoConfigurationEnabled()) {
      return;
    }

    List<MavenProject> projects = session.getProjects();
    ExecutorService projectConfigurationPool = createProjectConfigurationPool(projects.size());
    Map<Path, Collection<MojoExecution>> testExecutions =
        configureProjects(projectConfigurationPool, session, projects);
    configureTestExecutions(projectConfigurationPool, session, testExecutions);
    projectConfigurationPool.shutdown();
  }

  private static ExecutorService createProjectConfigurationPool(int projectsCount) {
    int projectConfigurationPoolSize =
        Math.min(projectsCount, Runtime.getRuntime().availableProcessors() * 2);
    AgentThreadFactory threadFactory =
        new AgentThreadFactory(AgentThreadFactory.AgentThread.CI_PROJECT_CONFIGURATOR);
    return Executors.newFixedThreadPool(projectConfigurationPoolSize, threadFactory);
  }

  private Map<Path, Collection<MojoExecution>> configureProjects(
      ExecutorService projectConfigurationPool, MavenSession session, List<MavenProject> projects) {
    CompletionService<Map<Path, Collection<MojoExecution>>> testExecutionsCompletionService =
        new ExecutorCompletionService<>(projectConfigurationPool);
    for (MavenProject project : projects) {
      testExecutionsCompletionService.submit(() -> configureProject(session, project));
    }

    Map<Path, Collection<MojoExecution>> testExecutions = new HashMap<>();
    for (int i = 0; i < projects.size(); i++) {
      try {
        Future<Map<Path, Collection<MojoExecution>>> future =
            testExecutionsCompletionService.take();
        for (Map.Entry<Path, Collection<MojoExecution>> e : future.get().entrySet()) {
          Path path = e.getKey();
          Collection<MojoExecution> executions = e.getValue();
          testExecutions.computeIfAbsent(path, k -> new ArrayList<>()).addAll(executions);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.error("Interrupted while configuring projects", e);
      } catch (ExecutionException e) {
        LOGGER.error("Error while configuring projects", e);
      }
    }
    return testExecutions;
  }

  private Map<Path, Collection<MojoExecution>> configureProject(
      MavenSession session, MavenProject project) {
    Properties projectProperties = project.getProperties();
    String projectArgLine = projectProperties.getProperty("argLine");
    if (projectArgLine == null) {
      // otherwise reference to "@{argLine}" that we add when configuring tracer
      // might cause failure
      projectProperties.setProperty("argLine", "");
    }

    Config config = Config.get();
    if (config.isCiVisibilityCompilerPluginAutoConfigurationEnabled()) {
      String compilerPluginVersion = config.getCiVisibilityCompilerPluginVersion();
      MavenProjectConfigurator.INSTANCE.configureCompilerPlugin(project, compilerPluginVersion);
    }

    MavenProjectConfigurator.INSTANCE.configureJacoco(project);

    return getTestExecutionsByJvmPath(session, project);
  }

  private Map<Path, Collection<MojoExecution>> getTestExecutionsByJvmPath(
      MavenSession session, MavenProject project) {
    Map<Path, Collection<MojoExecution>> testExecutions = new HashMap<>();
    try {
      PlexusContainer container = session.getContainer();

      MavenPluginManager mavenPluginManager = container.lookup(MavenPluginManager.class);
      BuildPluginManager buildPluginManager = container.lookup(BuildPluginManager.class);
      LifecycleExecutionPlanCalculator planCalculator =
          container.lookup(LifecycleExecutionPlanCalculator.class);

      List<Object> tasks = session.getGoals().stream().map(LifecycleTask::new).collect(toList());
      MavenExecutionPlan executionPlan =
          planCalculator.calculateExecutionPlan(session, project, tasks);

      for (MojoExecution mojoExecution : executionPlan.getMojoExecutions()) {
        Plugin plugin = mojoExecution.getPlugin();
        String pluginKey = plugin.getKey();

        if (MavenUtils.isTestExecution(mojoExecution)) {
          MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
          PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();
          // ensure plugin realm is loaded in container
          buildPluginManager.getPluginRealm(session, pluginDescriptor);

          Mojo mojo = mavenPluginManager.getConfiguredMojo(Mojo.class, session, mojoExecution);
          String forkedJvm = getEffectiveJvm(mojo);
          Path forkedJvmPath = forkedJvm != null ? Paths.get(forkedJvm) : null;
          if (forkedJvmPath == null) {
            LOGGER.warn(
                "Could not determine forked JVM path for plugin {} execution {} in project {}",
                pluginKey,
                mojoExecution.getExecutionId(),
                project.getName());
          }

          testExecutions.computeIfAbsent(forkedJvmPath, k -> new ArrayList<>()).add(mojoExecution);
        }
      }
      return testExecutions;

    } catch (Exception e) {
      LOGGER.error(
          "Error while getting test executions for session {} and project {}", session, project, e);
      return testExecutions;
    }
  }

  private String getEffectiveJvm(Mojo mojo) {
    Method getEffectiveJvmMethod = findGetEffectiveJvmMethod(mojo.getClass());
    if (getEffectiveJvmMethod == null) {
      return null;
    }
    getEffectiveJvmMethod.setAccessible(true);
    try {
      // result type differs based on Maven version
      Object effectiveJvm = getEffectiveJvmMethod.invoke(mojo);

      if (effectiveJvm instanceof String) {
        return (String) effectiveJvm;
      } else if (effectiveJvm instanceof File) {
        return ((File) effectiveJvm).getAbsolutePath();
      }

      Class<?> effectiveJvmClass = effectiveJvm.getClass();
      Method getJvmExecutableMethod = effectiveJvmClass.getMethod("getJvmExecutable");
      Object jvmExecutable = getJvmExecutableMethod.invoke(effectiveJvm);

      if (jvmExecutable instanceof String) {
        return (String) jvmExecutable;
      } else if (jvmExecutable instanceof File) {
        return ((File) jvmExecutable).getAbsolutePath();
      } else {
        return null;
      }

    } catch (Exception e) {
      return null;
    }
  }

  private Method findGetEffectiveJvmMethod(Class<?> mojoClass) {
    do {
      try {
        return mojoClass.getDeclaredMethod("getEffectiveJvm");
      } catch (NoSuchMethodException e) {
        // continue
      }
      mojoClass = mojoClass.getSuperclass();
    } while (mojoClass != null);
    return null;
  }

  private void configureTestExecutions(
      ExecutorService projectConfigurationPool,
      MavenSession session,
      Map<Path, Collection<MojoExecution>> testExecutions) {
    CompletionService<Void> configurationCompletionService =
        new ExecutorCompletionService<>(projectConfigurationPool);
    for (Map.Entry<Path, Collection<MojoExecution>> e : testExecutions.entrySet()) {
      Path jvmExecutablePath = e.getKey();
      Collection<MojoExecution> executions = e.getValue();
      configurationCompletionService.submit(
          () -> configureTestExecutions(session, jvmExecutablePath, executions));
    }

    for (int i = 0; i < testExecutions.size(); i++) {
      try {
        Future<Void> future = configurationCompletionService.take();
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.error("Interrupted while configuring test executions", e);
      } catch (ExecutionException e) {
        LOGGER.error("Error while configuring test executions", e);
      }
    }
  }

  private Void configureTestExecutions(
      MavenSession session, Path jvmExecutablePath, Collection<MojoExecution> testExecutions) {
    MavenExecutionRequest request = session.getRequest();
    ModuleExecutionSettings moduleExecutionSettings =
        buildEventsHandler.getModuleExecutionSettings(request, jvmExecutablePath);

    for (MojoExecution testExecution : testExecutions) {
      MavenProjectConfigurator.INSTANCE.configureTracer(
          testExecution, moduleExecutionSettings.getSystemProperties());
    }
    return null;
  }
}
