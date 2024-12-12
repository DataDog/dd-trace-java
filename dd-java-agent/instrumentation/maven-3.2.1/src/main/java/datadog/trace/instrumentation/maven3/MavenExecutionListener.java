package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.api.civisibility.domain.SourceSet;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import javax.annotation.Nullable;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenExecutionListener extends AbstractExecutionListener {

  private static final Logger log = LoggerFactory.getLogger(MavenExecutionListener.class);

  private final BuildEventsHandler<MavenExecutionRequest> buildEventsHandler;

  public MavenExecutionListener(BuildEventsHandler<MavenExecutionRequest> buildEventsHandler) {
    this.buildEventsHandler = buildEventsHandler;
  }

  @Override
  public void sessionEnded(ExecutionEvent event) {
    MavenSession session = event.getSession();
    MavenExecutionRequest request = session.getRequest();

    MavenExecutionResult result = session.getResult();
    if (result.hasExceptions()) {
      Throwable exception = MavenUtils.getException(result);
      buildEventsHandler.onTestSessionFail(request, exception);
    }

    buildEventsHandler.onTestSessionFinish(request);
  }

  @Override
  public void mojoSkipped(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isTestExecution(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenExecutionRequest request = session.getRequest();
      MavenProject project = event.getProject();
      String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);

      mojoStarted(event);
      buildEventsHandler.onTestModuleSkip(request, moduleName, null);
      mojoSucceeded(event);
    } else {
      mojoStarted(event);
      mojoSucceeded(event);
    }
  }

  @Override
  public void mojoStarted(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    MavenSession session = event.getSession();
    MavenExecutionRequest request = session.getRequest();
    MavenProject project = event.getProject();
    String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);

    if (!MavenUtils.isTestExecution(mojoExecution)) {
      Map<String, Object> additionalTags = new HashMap<>();
      additionalTags.put("project", project.getName());
      additionalTags.put("plugin", mojoExecution.getArtifactId());
      additionalTags.put("execution", mojoExecution.getExecutionId());
      buildEventsHandler.onBuildTaskStart(request, moduleName, additionalTags);
      return;
    }

    Build build = project.getBuild();
    SourceSet classes =
        getSourceSet(SourceSet.Type.CODE, build.getSourceDirectory(), build.getOutputDirectory());
    SourceSet tests =
        getSourceSet(
            SourceSet.Type.TEST, build.getTestSourceDirectory(), build.getTestOutputDirectory());
    BuildModuleLayout moduleLayout = new BuildModuleLayout(Arrays.asList(classes, tests));

    Path forkedJvmPath = MavenUtils.getForkedJvmPath(session, mojoExecution);
    List<Path> classpath = MavenUtils.getClasspath(session, mojoExecution);
    String executionId =
        mojoExecution.getPlugin().getArtifactId()
            + ":"
            + mojoExecution.getGoal()
            + ":"
            + mojoExecution.getExecutionId();
    Map<String, Object> additionalTags = Collections.singletonMap(Tags.TEST_EXECUTION, executionId);
    JavaAgent jacocoAgent = MavenUtils.getJacocoAgent(session, project, mojoExecution);

    BuildModuleSettings moduleSettings =
        buildEventsHandler.onTestModuleStart(
            request,
            moduleName,
            moduleLayout,
            forkedJvmPath,
            classpath,
            jacocoAgent,
            additionalTags);

    String forkCount = MavenUtils.getConfigurationValue(session, mojoExecution, "forkCount");
    if ("0".equals(forkCount)) {
      log.warn(
          "Tests execution {} does not run in a forked JVM, this configuration is not supported",
          executionId);
      return;
    }

    Map<String, String> systemProperties = moduleSettings.getSystemProperties();
    MavenProjectConfigurator.INSTANCE.configureTracer(
        session, project, mojoExecution, systemProperties, Config.get());
  }

  @Nullable
  private SourceSet getSourceSet(SourceSet.Type type, String source, String output) {
    if (source == null || output == null) {
      return null;
    }
    return new SourceSet(
        type, Collections.singleton(new File(source)), Collections.singleton(new File(output)));
  }

  @Override
  public void mojoSucceeded(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    MavenSession session = event.getSession();
    MavenExecutionRequest request = session.getRequest();
    MavenProject project = event.getProject();
    String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);

    if (MavenUtils.isTestExecution(mojoExecution)) {
      buildEventsHandler.onTestModuleFinish(request, moduleName);
    } else {
      buildEventsHandler.onBuildTaskFinish(request, moduleName);
    }
  }

  @Override
  public void mojoFailed(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    MavenSession session = event.getSession();
    MavenExecutionRequest request = session.getRequest();
    MavenProject project = event.getProject();
    String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);
    Exception exception = event.getException();

    if (MavenUtils.isTestExecution(mojoExecution)) {
      buildEventsHandler.onTestModuleFail(request, moduleName, exception);
      buildEventsHandler.onTestModuleFinish(request, moduleName);
    } else {
      buildEventsHandler.onBuildTaskFail(request, moduleName, exception);
      buildEventsHandler.onBuildTaskFinish(request, moduleName);
    }
  }
}
