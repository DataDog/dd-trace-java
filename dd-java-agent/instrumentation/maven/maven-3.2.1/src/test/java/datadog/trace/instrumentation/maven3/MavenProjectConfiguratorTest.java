package datadog.trace.instrumentation.maven3;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Collections;
import java.util.stream.Stream;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenProjectConfiguratorTest extends AbstractMavenTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenProjectConfiguratorTest.class);

  public static Stream<Arguments> surefireVersions() {
    return Stream.of(
        Arguments.of(
            "sampleProject/pom.xml", "test", new String[] {"-X", "-DargLine=-DmyArgLineProp=true"}),
        Arguments.of(
            "sampleProject/pom.xml",
            "surefire:test",
            new String[] {"-X", "-DargLine=-DmyArgLineProp=true"}),
        Arguments.of("sampleProjectArgLine/pom.xml", "test", new String[] {"-X"}),
        Arguments.of("sampleProjectArgLine/pom.xml", "surefire:test", new String[] {"-X"}),
        Arguments.of("sampleProjectSurefireArgLine/pom.xml", "test", new String[] {"-X"}),
        Arguments.of("sampleProjectSurefireArgLine/pom.xml", "surefire:test", new String[] {"-X"}),
        Arguments.of(
            "sampleProjectSurefireLateProcessingArgLine/pom.xml", "test", new String[] {"-X"}));
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testTracerInjection(String pomPath, String goal, String[] additionalCmdLineArgs)
      throws Exception {
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

      assertTrue(javaAgentInjected, "Tracer wasn't injected");
      assertTrue(argLinePreserved, "Original argLine was not preserved");

    } catch (Exception | Error e) {
      LOGGER.info("Build output:\n\n{}", stdOutBaos);
      LOGGER.info("Build error:\n\n{}", stdErrBaos);
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
