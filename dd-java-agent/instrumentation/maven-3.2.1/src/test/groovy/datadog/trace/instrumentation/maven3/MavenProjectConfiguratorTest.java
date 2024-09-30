package datadog.trace.instrumentation.maven3;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class MavenProjectConfiguratorTest extends AbstractMavenTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenProjectConfiguratorTest.class);

  @Parameterized.Parameters(name = "{0} - {1}")
  public static Collection<Object[]> surefireVersions() {
    return Arrays.asList(
        new Object[][] {
          {"sampleProject/pom.xml", "test", new String[] {"-X", "-DargLine=-DmyArgLineProp=true"}},
          {
            "sampleProject/pom.xml",
            "surefire:test",
            new String[] {"-X", "-DargLine=-DmyArgLineProp=true"}
          },
          {"sampleProjectArgLine/pom.xml", "test", new String[] {"-X"}},
          {"sampleProjectArgLine/pom.xml", "surefire:test", new String[] {"-X"}},
          {"sampleProjectSurefireArgLine/pom.xml", "test", new String[] {"-X"}},
          {"sampleProjectSurefireArgLine/pom.xml", "surefire:test", new String[] {"-X"}},
          {"sampleProjectSurefireLateProcessingArgLine/pom.xml", "test", new String[] {"-X"}},
        });
  }

  private final String pomPath;
  private final String goal;
  private final String[] additionalCmdLineArgs;

  public MavenProjectConfiguratorTest(String pomPath, String goal, String[] additionalCmdLineArgs) {
    this.pomPath = pomPath;
    this.goal = goal;
    this.additionalCmdLineArgs = additionalCmdLineArgs;
  }

  @Test
  public void testTracerInjection() throws Exception {
    ByteArrayOutputStream stdOutBaos = new ByteArrayOutputStream();
    PrintStream buildOutput = new PrintStream(stdOutBaos);

    ByteArrayOutputStream stdErrBaos = new ByteArrayOutputStream();
    PrintStream buildError = new PrintStream(stdErrBaos);

    try {
      executeMaven(
          this::injectTracer, pomPath, goal, buildOutput, buildError, additionalCmdLineArgs);

      boolean javaAgentInjected = false;
      boolean argLinePreserved = false;

      byte[] buildOutputBytes = stdOutBaos.toByteArray();
      BufferedReader buildOutputReader =
          new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buildOutputBytes)));
      String buildOutputLine;
      while ((buildOutputLine = buildOutputReader.readLine()) != null) {
        javaAgentInjected |= buildOutputLine.contains("TEST JAVA AGENT STARTED");
        argLinePreserved |=
            buildOutputLine.contains("surefire")
                && buildOutputLine.contains("Forking command line")
                && buildOutputLine.contains("-DmyArgLineProp=true");
      }

      assertTrue("Tracer wasn't injected", javaAgentInjected);
      assertTrue("Original argLine was not preserved", argLinePreserved);

    } catch (Exception | Error e) {
      LOGGER.info("Build output:\n\n" + stdOutBaos);
      LOGGER.info("Build error:\n\n" + stdErrBaos);
      throw e;
    }
  }

  private boolean injectTracer(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    MavenProject project = executionEvent.getProject();

    try {
      Config config = mock(Config.class);
      when(config.isCiVisibilityAutoConfigurationEnabled()).thenReturn(true);
      when(config.getCiVisibilityDebugPort()).thenReturn(null);
      when(config.getCiVisibilityAgentJarFile())
          .thenReturn(new File(MavenUtilsTest.class.getResource("simple-agent.jar").toURI()));
      MavenProjectConfigurator.INSTANCE.configureTracer(
          session, project, mojoExecution, Collections.emptyMap(), config);
      return true;

    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
}
