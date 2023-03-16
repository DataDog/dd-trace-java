package datadog.trace.instrumentation.maven3;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.util.Strings;
import java.io.File;
import java.util.Collection;
import java.util.Properties;
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

  private static final String ARG_LINE_PROPERTY_NAME = "argLine";

  private final BuildEventsHandler<MavenSession> buildEventsHandler =
      InstrumentationBridge.getBuildEventsHandler();

  @Override
  public void sessionStarted(ExecutionEvent event) {
    MavenSession session = event.getSession();

    MavenProject currentProject = session.getCurrentProject();
    File projectRoot = currentProject.getBasedir();
    TestDecorator mavenDecorator = new MavenDecorator(projectRoot.toPath());

    String projectName = currentProject.getName();
    String startCommand = MavenUtils.getCommandLine(session);
    String mavenVersion = MavenUtils.getMavenVersion(session);

    buildEventsHandler.onTestSessionStart(
        session, mavenDecorator, projectName, startCommand, "maven", mavenVersion);
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

  // FIXME additional tags to differentiate test runs
  //  mojoExecution.getExecutionId() // default
  //      mojoExecution.getGoal() // integration-test
  //      mojoExecution.getPlugin().getArtifactId() // maven-failsafe-plugin
  //
  //// GRADLE??? what if there are several Test tasks for a single module
  //// use test traits to differentiate

  @Override
  public void mojoStarted(ExecutionEvent event) {
    MojoExecution mojoExecution = event.getMojoExecution();
    if (MavenUtils.isMavenSurefireTest(mojoExecution)
        || MavenUtils.isMavenFailsafeTest(mojoExecution)) {
      // FIXME mojoExecution.getMojoDescriptor().getParameterMap() ---- check for forking; how to
      // handle if not forked????
      // FIXME see org.apache.maven.plugin.surefire.AbstractSurefireMojo.isForking (compileOnly
      // group: 'org.apache.maven.plugins', name: 'maven-surefire-plugin', version: '3.0.0-M8')

      MavenSession session = event.getSession();
      MavenProject project = event.getProject();
      String projectName = project.getName();
      String lifecyclePhase = mojoExecution.getLifecyclePhase();
      String moduleName = projectName + " " + lifecyclePhase;
      String startCommand = MavenUtils.getCommandLine(session);
      BuildEventsHandler.ModuleAndSessionId moduleAndSessionId =
          buildEventsHandler.onTestModuleStart(session, moduleName, startCommand);

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
      String argLine = MavenUtils.getConfigurationValue(configuration, ARG_LINE_PROPERTY_NAME);

      if (argLine == null) {
        argLine = "";
      }
      // FIXME write a generic property replacement mechanism
      if (argLine.contains("${argLine}")) {
        // when <argLine> is not set explicitly in plugin configuration,
        // the resulting config property will have the value "${argLine}",
        // essentially meaning that it should be taken from the property with the same name.
        // This property might be present or not, under usual circumstances this
        // makes no difference.
        // However, for some strange reason, if we modify the arg line programmatically,
        // missing property will cause the build to fail.
        // Which is why here we check if the property is missing and fill it in
        // if that is the case
        Properties projectProperties = project.getProperties();
        String argLineProjectProperty = projectProperties.getProperty("argLine");

        Properties userProperties = session.getUserProperties();
        String argLineUserProperty = userProperties.getProperty("argLine");

        Properties systemProperties = session.getSystemProperties();
        String argLineSystemProperty = systemProperties.getProperty("argLine");

        if (argLineProjectProperty == null
            && argLineUserProperty == null
            && argLineSystemProperty == null) {
          projectProperties.put(ARG_LINE_PROPERTY_NAME, "");
        }
      }

      argLine +=
          " " + arg(CiVisibilityConfig.CIVISIBILITY_SESSION_ID, moduleAndSessionId.sessionId);
      argLine += " " + arg(CiVisibilityConfig.CIVISIBILITY_MODULE_ID, moduleAndSessionId.moduleId);

      Xpp3Dom modifiedConfiguration =
          MavenUtils.setConfigurationValue(argLine, configuration, ARG_LINE_PROPERTY_NAME);
      mojoExecution.setConfiguration(modifiedConfiguration);
    }
  }

  private static String arg(String propertyName, Object value) {
    return "-D" + Strings.propertyNameToSystemPropertyName(propertyName) + "=" + value;
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
    }
  }
}
