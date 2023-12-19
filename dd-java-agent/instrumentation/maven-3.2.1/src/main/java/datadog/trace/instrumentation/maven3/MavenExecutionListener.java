package datadog.trace.instrumentation.maven3;

import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public class MavenExecutionListener extends AbstractExecutionListener {

  private static final String FORK_COUNT_CONFIG = "forkCount";

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
    }
  }

  @Override
  public void mojoStarted(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isTestExecution(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenExecutionRequest request = session.getRequest();
      MavenProject project = event.getProject();
      String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);

      String outputClassesDir = project.getBuild().getOutputDirectory();
      Collection<File> outputClassesDirs =
          outputClassesDir != null
              ? Collections.singleton(new File(outputClassesDir))
              : Collections.emptyList();

      String executionId =
          mojoExecution.getPlugin().getArtifactId()
              + ":"
              + mojoExecution.getGoal()
              + ":"
              + mojoExecution.getExecutionId();
      Map<String, Object> additionalTags =
          Collections.singletonMap(Tags.TEST_EXECUTION, executionId);

      BuildEventsHandler.ModuleInfo moduleInfo =
          buildEventsHandler.onTestModuleStart(
              request, moduleName, outputClassesDirs, additionalTags);

      Xpp3Dom configuration = mojoExecution.getConfiguration();
      boolean forkTestVm =
          !"0".equals(MavenUtils.getConfigurationValue(configuration, FORK_COUNT_CONFIG));
      if (forkTestVm) {
        configuration =
            setForkedVmSystemProperty(
                configuration, CiVisibilityConfig.CIVISIBILITY_SESSION_ID, moduleInfo.sessionId);
        configuration =
            setForkedVmSystemProperty(
                configuration, CiVisibilityConfig.CIVISIBILITY_MODULE_ID, moduleInfo.moduleId);
        configuration =
            setForkedVmSystemProperty(
                configuration,
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST,
                moduleInfo.signalServerHost);
        configuration =
            setForkedVmSystemProperty(
                configuration,
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT,
                moduleInfo.signalServerPort);

        mojoExecution.setConfiguration(configuration);
      } else {
        // set session/module ID props to let tests instrumentation code know
        // that it shouldn't create its own module event
        System.setProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID),
            String.valueOf(moduleInfo.sessionId));
        System.setProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID),
            String.valueOf(moduleInfo.moduleId));
      }
    }
  }

  private static Xpp3Dom setForkedVmSystemProperty(
      Xpp3Dom configuration, String propertyName, Object propertyValue) {
    String argLine =
        MavenUtils.getConfigurationValue(configuration, "argLine")
            + " -D"
            + Strings.propertyNameToSystemPropertyName(propertyName)
            + '='
            + propertyValue;
    return MavenUtils.setConfigurationValue(argLine, configuration, "argLine");
  }

  @Override
  public void mojoSucceeded(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isTestExecution(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenExecutionRequest request = session.getRequest();
      MavenProject project = event.getProject();
      String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);
      buildEventsHandler.onTestModuleFinish(request, moduleName);

      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
    }
  }

  @Override
  public void mojoFailed(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isTestExecution(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenExecutionRequest request = session.getRequest();
      MavenProject project = event.getProject();
      String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);
      Exception exception = event.getException();
      buildEventsHandler.onTestModuleFail(request, moduleName, exception);
      buildEventsHandler.onTestModuleFinish(request, moduleName);

      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
    }
  }
}
