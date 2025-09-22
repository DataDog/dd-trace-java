package datadog.trace.instrumentation.maven3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.civisibility.domain.JavaAgent;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class MavenUtilsTest extends AbstractMavenTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenUtilsTest.class);

  private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_30);

  static {
    FREEMARKER.setClassForTemplateLoading(MavenUtilsTest.class, "");
    FREEMARKER.setDefaultEncoding("UTF-8");
    FREEMARKER.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    FREEMARKER.setLogTemplateExceptions(true);
    FREEMARKER.setWrapUncheckedExceptions(true);
  }

  private static final ComparableVersion SUREFIRE_3_0_0 = new ComparableVersion("3.0.0");
  private static final ComparableVersion SUREFIRE_3_2_0 = new ComparableVersion("3.2.0");
  private static final ComparableVersion MAVEN_3_3_1 = new ComparableVersion("3.3.1");

  public static Stream<Arguments> surefireVersions() {
    return Stream.of(
        Arguments.of(new ComparableVersion("2.17"), new ComparableVersion("3.0.0")),
        Arguments.of(new ComparableVersion("2.21.0"), new ComparableVersion("3.0.0")),
        Arguments.of(new ComparableVersion("3.0.0"), new ComparableVersion("3.0.0")),
        Arguments.of(new ComparableVersion("3.5.0"), new ComparableVersion("3.6.3")),
        Arguments.of(
            new ComparableVersion(getLatestMavenSurefireVersion()),
            new ComparableVersion("3.6.3")));
  }

  private ComparableVersion getCurrentMavenVersion() {
    return new ComparableVersion(MavenSession.class.getPackage().getImplementationVersion());
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetMojoConfigValueReturnsNullIfValueNotSet(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetMojoConfigValueReturnsNullIfValueNotSet,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetMojoConfigValueReturnsNullIfValueNotSet(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    String forkCount = MavenUtils.getConfigurationValue(session, mojoExecution, "forkCount");
    assertNull(forkCount);
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetMojoConfigValueReturnsConfiguredValue(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetMojoConfigValueReturnsConfiguredValue,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetMojoConfigValueReturnsConfiguredValue(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    String threadCount = MavenUtils.getConfigurationValue(session, mojoExecution, "threadCount");
    assertEquals("112", threadCount);
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetMojoConfigValueResolvesPropertyPlaceholders(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetMojoConfigValueResolvesPropertyPlaceholders,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetMojoConfigValueResolvesPropertyPlaceholders(
      ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    String forkedProcessExitTimeoutInSeconds =
        MavenUtils.getConfigurationValue(session, mojoExecution, "forkedProcessTimeoutInSeconds");
    assertEquals("887", forkedProcessExitTimeoutInSeconds);
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetMojoConfigValueResolvesPropertiesSuppliedViaCmdLine(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetMojoConfigValueResolvesPropertiesSuppliedViaCmdLine,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion),
        "-Dsurefire.parallel.timeout=112233");
  }

  private boolean assertGetMojoConfigValueResolvesPropertiesSuppliedViaCmdLine(
      ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    String parallelTestsTimeoutInSeconds =
        MavenUtils.getConfigurationValue(session, mojoExecution, "parallelTestsTimeoutInSeconds");
    assertEquals("112233", parallelTestsTimeoutInSeconds);
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetArgLineResolvesLatePropertyPlaceholders(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetArgLineResolvesLatePropertyPlaceholders,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetArgLineResolvesLatePropertyPlaceholders(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    MavenProject project = executionEvent.getProject();
    String argLine = MavenUtils.getArgLine(session, project, mojoExecution);
    assertEquals("-Xms128m -Xmx2g", argLine);
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetJacocoAgent(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(this::assertGetJacocoAgent, "samplePomJacoco.xml", "test");
  }

  private boolean assertGetJacocoAgent(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    MavenProject project = executionEvent.getProject();
    JavaAgent jacocoAgent = MavenUtils.getJacocoAgent(session, project, mojoExecution);
    assertNotNull(jacocoAgent);
    assertTrue(jacocoAgent.getPath().endsWith("org.jacoco.agent-0.8.11-runtime.jar"));
    assertNotNull(jacocoAgent.getArguments());
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetEffectiveJvmFallbackUsesJvmProperty(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetEffectiveJvmFallbackUsesJvmProperty,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetEffectiveJvmFallbackUsesJvmProperty(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    String effectiveJvm = MavenUtils.getEffectiveJvmFallback(session, mojoExecution);
    assertEquals("jvm-config-property-value", effectiveJvm);
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetEffectiveJvmFallbackUsesToolchains(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    assumeTrue(surefirePluginVersion.compareTo(SUREFIRE_3_0_0) >= 0);
    assumeTrue(getCurrentMavenVersion().compareTo(MAVEN_3_3_1) >= 0);

    File toolchainsFile = createToolchainsFile();
    executeMaven(
        this::assertGetEffectiveJvmFallbackUsesToolchains,
        "samplePomToolchains.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion),
        "--global-toolchains",
        toolchainsFile.getAbsolutePath());
  }

  private boolean assertGetEffectiveJvmFallbackUsesToolchains(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }

    MavenSession session = executionEvent.getSession();
    String effectiveJvm = MavenUtils.getEffectiveJvmFallback(session, mojoExecution);
    assertNotNull(effectiveJvm);
    assertTrue(effectiveJvm.endsWith("/my-jdk-home/bin/java"));
    return true;
  }

  /**
   * "jdkHome" in toolchains.xml has to point to an existing folder, so this method processes
   * toolchains template, substituting the path to the real my-jdk-home directory.
   */
  private static File createToolchainsFile()
      throws URISyntaxException, IOException, TemplateException {
    File toolchainJdkHome = new File(MavenUtilsTest.class.getResource("my-jdk-home").toURI());
    Map<String, String> replacements =
        Collections.singletonMap("my_jdk_home_path", toolchainJdkHome.getAbsolutePath());

    File toolchainsFile = WORKING_DIRECTORY.resolve("toolchains.xml").toFile();
    try (FileWriter toolchainsFileWriter = new FileWriter(toolchainsFile)) {
      Template coveragesTemplate = FREEMARKER.getTemplate("sampleToolchains.ftl");
      coveragesTemplate.process(replacements, toolchainsFileWriter);
    }
    return toolchainsFile;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetForkedJvmPath(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetForkedJvmPath,
        "samplePomJacoco.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetForkedJvmPath(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    Path jvmPath = MavenUtils.getForkedJvmPath(session, mojoExecution);
    assertNotNull(jvmPath);
    assertTrue(jvmPath.toString().endsWith("/java"));
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetClasspath(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetClasspath,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private void assertClasspath(Collection<Path> classpath, String... suffixes) {
    assertNotNull(classpath);
    assertEquals(suffixes.length, classpath.size());

    for (String suffix : suffixes) {
      assertFalse(
          classpath.stream().noneMatch(c -> c.toString().endsWith(suffix)),
          "Missing entry: " + suffix);
    }
  }

  private boolean assertGetClasspath(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }

    MavenSession session = executionEvent.getSession();
    List<Path> classpath = MavenUtils.getClasspath(session, mojoExecution);
    assertClasspath(
        classpath,
        "/test-classes",
        "/classes",
        "/junit-4.13.2.jar",
        "/hamcrest-core-1.3.jar",
        "/commons-lang3-3.17.0.jar");
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetClasspathConsidersAdditionalClasspathDependencies(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    assumeTrue(surefirePluginVersion.compareTo(SUREFIRE_3_2_0) >= 0);
    executeMaven(
        this::assertGetClasspathConsidersAdditionalClasspathDependencies,
        "samplePomAdditionalClasspathDependencies.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetClasspathConsidersAdditionalClasspathDependencies(
      ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }

    MavenSession session = executionEvent.getSession();
    List<Path> classpath = MavenUtils.getClasspath(session, mojoExecution);
    assertClasspath(
        classpath,
        "/test-classes",
        "/classes",
        "/junit-4.13.2.jar",
        "/hamcrest-core-1.3.jar",
        "/commons-io-2.16.1.jar");
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetClasspathConsidersAdditionalClasspathElements(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetClasspathConsidersAdditionalClasspathElements,
        "samplePomAdditionalClasspathElements.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetClasspathConsidersAdditionalClasspathElements(
      ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }

    MavenSession session = executionEvent.getSession();
    List<Path> classpath = MavenUtils.getClasspath(session, mojoExecution);
    assertClasspath(
        classpath,
        "/test-classes",
        "/classes",
        "/junit-4.13.2.jar",
        "/hamcrest-core-1.3.jar",
        "/path/to/additional/classpath/element",
        "/path/to/another/additional/classpath/element");
    return true;
  }

  @ParameterizedTest
  @MethodSource("surefireVersions")
  public void testGetContainer(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion)
      throws Exception {
    assumeTrue(
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0,
        "Newer maven version required to run chosen version of Surefire plugin");
    executeMaven(
        this::assertGetContainer,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetContainer(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    PlexusContainer container = MavenUtils.getContainer(session);
    assertNotNull(container);
    return true;
  }

  private static String getLatestMavenSurefireVersion() {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(
                "https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml")
            .build();
    try (Response response = client.newCall(request).execute()) {
      if (response.isSuccessful()) {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(response.body().byteStream());
        doc.getDocumentElement().normalize();

        NodeList versionList = doc.getElementsByTagName("latest");
        if (versionList.getLength() > 0) {
          String version = versionList.item(0).getTextContent();
          if (!version.contains("alpha") && !version.contains("beta")) {
            LOGGER.info("Will run the 'latest' tests with version " + version);
            return version;
          }
        }
      } else {
        LOGGER.warn(
            "Could not get latest Maven Surefire version, response from repo.maven.apache.org is "
                + response.code()
                + ":"
                + response.body().string());
      }
    } catch (Exception e) {
      LOGGER.warn("Could not get latest Maven Surefire version", e);
    }
    String hardcodedLatestVersion = "3.5.0"; // latest version that is known to work
    LOGGER.info("Will run the 'latest' tests with hard-coded version " + hardcodedLatestVersion);
    return hardcodedLatestVersion;
  }
}
