package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.util.AgentThreadFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
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
import org.apache.maven.project.MavenProject;
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
        request, projectName, projectRoot, startCommand, "maven", mavenVersion, null);

    List<MavenProject> projects = session.getProjects();
    for (MavenProject project : projects) {
      Properties projectProperties = project.getProperties();
      String projectArgLine = projectProperties.getProperty("argLine");
      if (projectArgLine == null) {
        // otherwise reference to "@{argLine}" that we add when configuring tracer
        // might cause failure
        projectProperties.setProperty("argLine", "");
      }
    }

    if (!config.isCiVisibilityAutoConfigurationEnabled()) {
      return;
    }

    ExecutorService projectConfigurationPool = createProjectConfigurationPool(projects.size());
    configureProjects(projectConfigurationPool, session, projects);
    projectConfigurationPool.shutdown();
  }

  private static ExecutorService createProjectConfigurationPool(int projectsCount) {
    int projectConfigurationPoolSize =
        Math.min(projectsCount, Runtime.getRuntime().availableProcessors() * 2);
    AgentThreadFactory threadFactory =
        new AgentThreadFactory(AgentThreadFactory.AgentThread.CI_PROJECT_CONFIGURATOR);
    return Executors.newFixedThreadPool(projectConfigurationPoolSize, threadFactory);
  }

  private void configureProjects(
      ExecutorService projectConfigurationPool, MavenSession session, List<MavenProject> projects) {
    CompletionService<Void> testExecutionsCompletionService =
        new ExecutorCompletionService<>(projectConfigurationPool);
    for (MavenProject project : projects) {
      testExecutionsCompletionService.submit(() -> configureProject(session, project));
    }

    for (int i = 0; i < projects.size(); i++) {
      try {
        Future<Void> future = testExecutionsCompletionService.take();
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.error("Interrupted while configuring projects", e);
      } catch (ExecutionException e) {
        LOGGER.error("Error while configuring projects", e);
      }
    }
  }

  private Void configureProject(MavenSession session, MavenProject project) {
    MavenProjectConfigurator.INSTANCE.configureCompilerPlugin(project);

    MavenExecutionRequest request = session.getRequest();
    BuildSessionSettings sessionSettings = buildEventsHandler.getSessionSettings(request);
    MavenProjectConfigurator.INSTANCE.configureJacoco(session, project, sessionSettings);

    return null;
  }
}
