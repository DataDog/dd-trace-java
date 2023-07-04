package datadog.trace.instrumentation.maven3;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenExecutionListener extends AbstractExecutionListener {

  private static final Logger log = LoggerFactory.getLogger(MavenExecutionListener.class);

  private static final String FORK_COUNT_CONFIG = "forkCount";
  private static final String SYSTEM_PROPERTY_VARIABLES_CONFIG = "systemPropertyVariables";

  private final BuildEventsHandler<MavenSession> buildEventsHandler =
      InstrumentationBridge.createBuildEventsHandler();

  @Override
  public void sessionStarted(ExecutionEvent event) {
    MavenSession session = event.getSession();

    MavenProject currentProject = session.getCurrentProject();
    Path projectRoot = currentProject.getBasedir().toPath();

    String projectName = currentProject.getName();
    String startCommand = MavenUtils.getCommandLine(session);
    String mavenVersion = MavenUtils.getMavenVersion(session);

    buildEventsHandler.onTestSessionStart(
        session, projectName, projectRoot, startCommand, "maven", mavenVersion);

    Collection<MavenUtils.TestFramework> testFrameworks =
        MavenUtils.collectTestFrameworks(event.getProject());
    if (testFrameworks.size() == 1) {
      // if the module uses multiple test frameworks, we do not set the tags
      MavenUtils.TestFramework testFramework = testFrameworks.iterator().next();
      buildEventsHandler.onTestFrameworkDetected(
          session, testFramework.name, testFramework.version);
    } else if (testFrameworks.size() > 1) {
      log.info(
          "Multiple test frameworks detected: {}. Test framework data will not be populated",
          testFrameworks);
    }
  }

  @Override
  public void sessionEnded(ExecutionEvent event) {
    MavenSession session = event.getSession();

    MavenExecutionResult result = session.getResult();
    if (result.hasExceptions()) {
      Throwable exception = MavenUtils.getException(result);
      buildEventsHandler.onTestSessionFail(session, exception);
    }

    buildEventsHandler.onTestSessionFinish(session);
  }

  @Override
  public void mojoSkipped(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isMavenSurefireTest(mojoExecution)
        || MavenUtils.isMavenFailsafeTest(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenProject project = event.getProject();
      String projectName = project.getName();
      String lifecyclePhase = mojoExecution.getLifecyclePhase();
      String moduleName = projectName + " " + lifecyclePhase;

      mojoStarted(event);
      buildEventsHandler.onTestModuleSkip(session, moduleName, null);
      mojoSucceeded(event);
    }
  }

  @Override
  public void mojoStarted(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isMavenSurefireTest(mojoExecution)
        || MavenUtils.isMavenFailsafeTest(mojoExecution)) {

      MavenSession session = event.getSession();
      MavenProject project = event.getProject();
      String projectName = project.getName();
      String lifecyclePhase = mojoExecution.getLifecyclePhase();
      String moduleName = projectName + " " + lifecyclePhase;
      String startCommand = MavenUtils.getCommandLine(session);

      String executionId =
          mojoExecution.getPlugin().getArtifactId()
              + ":"
              + mojoExecution.getGoal()
              + ":"
              + mojoExecution.getExecutionId();
      Map<String, Object> additionalTags =
          Collections.singletonMap(Tags.TEST_EXECUTION, executionId);

      BuildEventsHandler.ModuleAndSessionId moduleAndSessionId =
          buildEventsHandler.onTestModuleStart(session, moduleName, startCommand, additionalTags);

      Collection<MavenUtils.TestFramework> testFrameworks =
          MavenUtils.collectTestFrameworks(project);
      if (testFrameworks.size() == 1) {
        // if the module uses multiple test frameworks, we do not set the tags
        MavenUtils.TestFramework testFramework = testFrameworks.iterator().next();
        buildEventsHandler.onModuleTestFrameworkDetected(
            session, moduleName, testFramework.name, testFramework.version);
      } else if (testFrameworks.size() > 1) {
        log.info(
            "Multiple test frameworks detected: {}. Test framework data will not be populated",
            testFrameworks);
      }

      Xpp3Dom configuration = mojoExecution.getConfiguration();
      boolean forkTestVm =
          !"0".equals(MavenUtils.getConfigurationValue(configuration, FORK_COUNT_CONFIG));
      if (forkTestVm) {
        configuration =
            setForkedVmSystemProperty(
                configuration,
                Strings.propertyNameToSystemPropertyName(
                    CiVisibilityConfig.CIVISIBILITY_SESSION_ID),
                moduleAndSessionId.sessionId);
        configuration =
            setForkedVmSystemProperty(
                configuration,
                Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID),
                moduleAndSessionId.moduleId);

        mojoExecution.setConfiguration(configuration);
      } else {
        // set session/module ID props to let tests instrumentation code know
        // that it shouldn't create its own module event
        System.setProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID),
            String.valueOf(moduleAndSessionId.sessionId));
        System.setProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID),
            String.valueOf(moduleAndSessionId.moduleId));
      }
    }
  }

  private static Xpp3Dom setForkedVmSystemProperty(
      Xpp3Dom configuration, String propertyName, Object propertyValue) {
    return MavenUtils.setConfigurationValue(
        String.valueOf(propertyValue),
        configuration,
        SYSTEM_PROPERTY_VARIABLES_CONFIG,
        propertyName);
  }

  @Override
  public void mojoSucceeded(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isMavenSurefireTest(mojoExecution)
        || MavenUtils.isMavenFailsafeTest(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenProject project = event.getProject();

      String projectName = project.getName();
      String lifecyclePhase = mojoExecution.getLifecyclePhase();
      String moduleName = projectName + " " + lifecyclePhase;
      buildEventsHandler.onTestModuleFinish(session, moduleName);

      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
    }
  }

  @Override
  public void mojoFailed(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isMavenSurefireTest(mojoExecution)
        || MavenUtils.isMavenFailsafeTest(mojoExecution)) {
      MavenSession session = event.getSession();
      MavenProject project = event.getProject();

      String projectName = project.getName();
      String lifecyclePhase = mojoExecution.getLifecyclePhase();
      String moduleName = projectName + " " + lifecyclePhase;

      Exception exception = event.getException();
      buildEventsHandler.onTestModuleFail(session, moduleName, exception);
      buildEventsHandler.onTestModuleFinish(session, moduleName);

      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      System.clearProperty(
          Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
    }
  }
}
