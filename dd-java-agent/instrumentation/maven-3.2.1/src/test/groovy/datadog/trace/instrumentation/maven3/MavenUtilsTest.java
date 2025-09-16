package datadog.trace.instrumentation.maven3;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.notNullValue;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

@RunWith(Parameterized.class)
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

  private final ComparableVersion surefirePluginVersion;

  public MavenUtilsTest(
      ComparableVersion surefirePluginVersion, ComparableVersion minRequiredMavenVersion) {
    Assume.assumeTrue(
        "Newer maven version required to run chosen version of Surefire plugin",
        minRequiredMavenVersion.compareTo(getCurrentMavenVersion()) <= 0);
    this.surefirePluginVersion = surefirePluginVersion;
  }

  private ComparableVersion getCurrentMavenVersion() {
    return new ComparableVersion(MavenSession.class.getPackage().getImplementationVersion());
  }

  @Test
  public void testGetMojoConfigValueReturnsNullIfValueNotSet() throws Exception {
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
    assertThat(forkCount, equalTo(null));
    return true;
  }

  @Test
  public void testGetMojoConfigValueReturnsConfiguredValue() throws Exception {
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
    assertThat(threadCount, equalTo("112"));
    return true;
  }

  @Test
  public void testGetMojoConfigValueResolvesPropertyPlaceholders() throws Exception {
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
    assertThat(forkedProcessExitTimeoutInSeconds, equalTo("887"));
    return true;
  }

  @Test
  public void testGetMojoConfigValueResolvesPropertiesSuppliedViaCmdLine() throws Exception {
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
    assertThat(parallelTestsTimeoutInSeconds, equalTo("112233"));
    return true;
  }

  @Test
  public void testGetArgLineResolvesLatePropertyPlaceholders() throws Exception {
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
    assertThat(argLine, equalTo("-Xms128m -Xmx2g"));
    return true;
  }

  @Test
  public void testGetJacocoAgent() throws Exception {
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
    assertThat(jacocoAgent, notNullValue());
    assertThat(jacocoAgent.getPath(), endsWith("org.jacoco.agent-0.8.11-runtime.jar"));
    assertThat(jacocoAgent.getArguments(), notNullValue());
    return true;
  }

  @Test
  public void testGetEffectiveJvmFallbackUsesJvmProperty() throws Exception {
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
    assertThat(effectiveJvm, equalTo("jvm-config-property-value"));
    return true;
  }

  @Test
  public void testGetEffectiveJvmFallbackUsesToolchains() throws Exception {
    Assume.assumeTrue(surefirePluginVersion.compareTo(SUREFIRE_3_0_0) >= 0);
    Assume.assumeTrue(getCurrentMavenVersion().compareTo(MAVEN_3_3_1) >= 0);

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
    assertThat(effectiveJvm, endsWith("/my-jdk-home/bin/java"));
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

    File toolchainsFile = WORKING_DIRECTORY.getRoot().resolve("toolchains.xml").toFile();
    try (FileWriter toolchainsFileWriter = new FileWriter(toolchainsFile)) {
      Template coveragesTemplate = FREEMARKER.getTemplate("sampleToolchains.ftl");
      coveragesTemplate.process(replacements, toolchainsFileWriter);
    }
    return toolchainsFile;
  }

  @Test
  public void testGetForkedJvmPath() throws Exception {
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
    assertThat(jvmPath, hasToString(endsWith("/java")));
    return true;
  }

  @Test
  public void testGetClasspath() throws Exception {
    executeMaven(
        this::assertGetClasspath,
        "samplePom.xml",
        String.format(
            "org.apache.maven.plugins:maven-surefire-plugin:%s:test", surefirePluginVersion));
  }

  private boolean assertGetClasspath(ExecutionEvent executionEvent) {
    MojoExecution mojoExecution = executionEvent.getMojoExecution();
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return false;
    }
    MavenSession session = executionEvent.getSession();
    List<Path> classpath = MavenUtils.getClasspath(session, mojoExecution);
    assertThat(classpath, hasSize(5));
    assertThat(classpath, hasItem(hasToString(endsWith("/test-classes"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/classes"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/junit-4.13.2.jar"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/hamcrest-core-1.3.jar"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/commons-lang3-3.17.0.jar"))));
    return true;
  }

  @Test
  public void testGetClasspathConsidersAdditionalClasspathDependencies() throws Exception {
    Assume.assumeTrue(surefirePluginVersion.compareTo(SUREFIRE_3_2_0) >= 0);
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
    assertThat(classpath, hasSize(5));
    assertThat(classpath, hasItem(hasToString(endsWith("/test-classes"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/classes"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/junit-4.13.2.jar"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/hamcrest-core-1.3.jar"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/commons-io-2.16.1.jar"))));
    return true;
  }

  @Test
  public void testGetClasspathConsidersAdditionalClasspathElements() throws Exception {
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
    assertThat(classpath, hasSize(6));
    assertThat(classpath, hasItem(hasToString(endsWith("/test-classes"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/classes"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/junit-4.13.2.jar"))));
    assertThat(classpath, hasItem(hasToString(endsWith("/hamcrest-core-1.3.jar"))));
    assertThat(classpath, hasItem(hasToString(equalTo("/path/to/additional/classpath/element"))));
    assertThat(
        classpath, hasItem(hasToString(equalTo("/path/to/another/additional/classpath/element"))));
    return true;
  }

  @Test
  public void testGetContainer() throws Exception {
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
    assertThat(container, notNullValue());
    return true;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> surefireVersions() {
    return Arrays.asList(
        new Object[][] {
          {new ComparableVersion("2.17"), new ComparableVersion("3.0.0")},
          {new ComparableVersion("2.21.0"), new ComparableVersion("3.0.0")},
          {new ComparableVersion("3.0.0"), new ComparableVersion("3.0.0")},
          {new ComparableVersion("3.5.0"), new ComparableVersion("3.6.3")},
          {new ComparableVersion(getLatestMavenSurefireVersion()), new ComparableVersion("3.6.3")}
        });
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
