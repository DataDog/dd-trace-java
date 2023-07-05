package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModuleExecutionSettingsFactory {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ModuleExecutionSettingsFactory.class);

  private final Config config;
  private final ConfigurationApi configurationApi;
  private final JvmInfoFactory jvmInfoFactory;
  private final GitDataUploader gitDataUploader;
  private final String repositoryRoot;

  public ModuleExecutionSettingsFactory(
      Config config,
      ConfigurationApi configurationApi,
      JvmInfoFactory jvmInfoFactory,
      GitDataUploader gitDataUploader,
      String repositoryRoot) {
    this.config = config;
    this.configurationApi = configurationApi;
    this.jvmInfoFactory = jvmInfoFactory;
    this.gitDataUploader = gitDataUploader;
    this.repositoryRoot = repositoryRoot;
  }

  public ModuleExecutionSettings create(Path jvmExecutablePath) {
    TracerEnvironment tracerEnvironment = buildTracerEnvironment(repositoryRoot, jvmExecutablePath);
    CiVisibilitySettings ciVisibilitySettings = getCiVisibilitySettings(tracerEnvironment);

    boolean codeCoverageEnabled = isCodeCoverageEnabled(ciVisibilitySettings);
    boolean itrEnabled = isItrEnabled(ciVisibilitySettings);
    Map<String, String> systemProperties =
        getPropertiesPropagatedToChildProcess(codeCoverageEnabled, itrEnabled);

    LOGGER.info(
        "Remote CI Visibility settings received: code coverage - {}, ITR - {}; {}, {}",
        codeCoverageEnabled,
        itrEnabled,
        repositoryRoot,
        jvmExecutablePath);

    Map<Path, List<SkippableTest>> skippableTestsByModulePath =
        itrEnabled && repositoryRoot != null
            ? getSkippableTestsByModulePath(Paths.get(repositoryRoot), tracerEnvironment)
            : Collections.emptyMap();

    return new ModuleExecutionSettings(systemProperties, skippableTestsByModulePath);
  }

  private TracerEnvironment buildTracerEnvironment(String repositoryRoot, Path jvmExecutablePath) {
    GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo(repositoryRoot);
    JvmInfo jvmInfo = jvmInfoFactory.getJvmInfo(jvmExecutablePath);

    /*
     * IMPORTANT: JVM and OS properties should match tags
     * set in datadog.trace.civisibility.decorator.TestDecorator
     */
    return TracerEnvironment.builder()
        .service(config.getServiceName())
        .env(config.getEnv())
        .repositoryUrl(gitInfo.getRepositoryURL())
        .branch(gitInfo.getBranch())
        .sha(gitInfo.getCommit().getSha())
        .osPlatform(System.getProperty("os.name"))
        .osArchitecture(System.getProperty("os.arch"))
        .osVersion(System.getProperty("os.version"))
        .runtimeName(jvmInfo.getName())
        .runtimeVersion(jvmInfo.getVersion())
        .runtimeVendor(jvmInfo.getVendor())
        .build();
  }

  private CiVisibilitySettings getCiVisibilitySettings(TracerEnvironment tracerEnvironment) {
    try {
      return configurationApi.getSettings(tracerEnvironment);
    } catch (Exception e) {
      LOGGER.warn(
          "Could not obtain CI Visibility settings, will default to disabled code coverage and tests skipping");
      LOGGER.debug("Error while obtaining CI Visibility settings", e);
      return new CiVisibilitySettings(false, false);
    }
  }

  private boolean isItrEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isTestsSkippingEnabled() && config.isCiVisibilityItrEnabled();
  }

  private boolean isCodeCoverageEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isCodeCoverageEnabled()
        && config.isCiVisibilityCodeCoverageEnabled();
  }

  private Map<String, String> getPropertiesPropagatedToChildProcess(
      boolean codeCoverageEnabled, boolean itrEnabled) {
    Map<String, String> propagatedSystemProperties = new HashMap<>();
    Properties systemProperties = System.getProperties();
    for (Map.Entry<Object, Object> e : systemProperties.entrySet()) {
      String propertyName = (String) e.getKey();
      Object propertyValue = e.getValue();
      if (propertyName.startsWith(Config.PREFIX) && propertyValue != null) {
        propagatedSystemProperties.put(propertyName, propertyValue.toString());
      }
    }

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED),
        Boolean.toString(codeCoverageEnabled));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED),
        Boolean.toString(itrEnabled));

    // explicitly disable build instrumentation in child processes,
    // because some projects run "embedded" Maven/Gradle builds as part of their integration tests,
    // and we don't want to show those as if they were regular build executions
    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED),
        Boolean.toString(false));

    return propagatedSystemProperties;
  }

  private Map<Path, List<SkippableTest>> getSkippableTestsByModulePath(
      Path repositoryRoot, TracerEnvironment tracerEnvironment) {
    try {
      // ensure git data upload is finished before asking for tests
      gitDataUploader
          .startOrObserveGitDataUpload()
          .get(config.getCiVisibilityGitUploadTimeoutMillis(), TimeUnit.MILLISECONDS);

      Collection<SkippableTest> skippableTests =
          configurationApi.getSkippableTests(tracerEnvironment);
      LOGGER.info(
          "Received {} skippable tests in total for {}", skippableTests.size(), repositoryRoot);

      return skippableTests.stream()
          .collect(
              Collectors.groupingBy(
                  t ->
                      t.getConfigurations() != null && t.getConfigurations().getTestBundle() != null
                          ? repositoryRoot.resolve(t.getConfigurations().getTestBundle())
                          : repositoryRoot));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted while waiting for git data upload", e);
      return Collections.emptyMap();

    } catch (Exception e) {
      LOGGER.error("Could not obtain list of skippable tests, will proceed without skipping", e);
      return Collections.emptyMap();
    }
  }
}
