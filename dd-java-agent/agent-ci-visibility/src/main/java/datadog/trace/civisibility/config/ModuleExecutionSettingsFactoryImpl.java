package datadog.trace.civisibility.config;

import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModuleExecutionSettingsFactoryImpl implements ModuleExecutionSettingsFactory {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ModuleExecutionSettingsFactoryImpl.class);
  private static final String TEST_CONFIGURATION_TAG_PREFIX = "test.configuration.";

  private final Config config;
  private final ConfigurationApi configurationApi;
  private final GitDataUploader gitDataUploader;
  private final RepoIndexProvider repoIndexProvider;
  private final String repositoryRoot;

  public ModuleExecutionSettingsFactoryImpl(
      Config config,
      ConfigurationApi configurationApi,
      GitDataUploader gitDataUploader,
      RepoIndexProvider repoIndexProvider,
      String repositoryRoot) {
    this.config = config;
    this.configurationApi = configurationApi;
    this.gitDataUploader = gitDataUploader;
    this.repoIndexProvider = repoIndexProvider;
    this.repositoryRoot = repositoryRoot;
  }

  @Override
  public ModuleExecutionSettings create(JvmInfo jvmInfo, @Nullable String moduleName) {
    TracerEnvironment tracerEnvironment =
        buildTracerEnvironment(repositoryRoot, jvmInfo, moduleName);
    CiVisibilitySettings ciVisibilitySettings = getCiVisibilitySettings(tracerEnvironment);

    boolean codeCoverageEnabled = isCodeCoverageEnabled(ciVisibilitySettings);
    boolean itrEnabled = isItrEnabled(ciVisibilitySettings);
    boolean flakyTestRetriesEnabled = isFlakyTestRetriesEnabled(ciVisibilitySettings);
    Map<String, String> systemProperties =
        getPropertiesPropagatedToChildProcess(
            codeCoverageEnabled, itrEnabled, flakyTestRetriesEnabled);

    LOGGER.info(
        "Remote CI Visibility settings received: per-test code coverage - {}, ITR - {}, flaky test retries - {}; {}, {}",
        codeCoverageEnabled,
        itrEnabled,
        flakyTestRetriesEnabled,
        repositoryRoot,
        jvmInfo);

    Map<String, Collection<TestIdentifier>> skippableTestsByModuleName =
        itrEnabled && repositoryRoot != null
            ? getSkippableTestsByModuleName(Paths.get(repositoryRoot), tracerEnvironment)
            : Collections.emptyMap();

    Collection<TestIdentifier> flakyTests =
        flakyTestRetriesEnabled ? getFlakyTests(tracerEnvironment) : Collections.emptyList();

    List<String> coverageEnabledPackages = getCoverageEnabledPackages(codeCoverageEnabled);
    return new ModuleExecutionSettings(
        codeCoverageEnabled,
        itrEnabled,
        flakyTestRetriesEnabled,
        systemProperties,
        skippableTestsByModuleName,
        flakyTests,
        coverageEnabledPackages);
  }

  private TracerEnvironment buildTracerEnvironment(
      String repositoryRoot, JvmInfo jvmInfo, @Nullable String moduleName) {
    GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo(repositoryRoot);

    TracerEnvironment.Builder builder = TracerEnvironment.builder();
    for (Map.Entry<String, String> e : config.getGlobalTags().entrySet()) {
      String key = e.getKey();
      if (key.startsWith(TEST_CONFIGURATION_TAG_PREFIX)) {
        String configurationKey = key.substring(TEST_CONFIGURATION_TAG_PREFIX.length());
        String configurationValue = e.getValue();
        builder.customTag(configurationKey, configurationValue);
      }
    }

    /*
     * IMPORTANT: JVM and OS properties should match tags
     * set in datadog.trace.civisibility.decorator.TestDecorator
     */
    return builder
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
        .testBundle(moduleName)
        .build();
  }

  private CiVisibilitySettings getCiVisibilitySettings(TracerEnvironment tracerEnvironment) {
    try {
      CiVisibilitySettings settings = configurationApi.getSettings(tracerEnvironment);
      if (settings.isGitUploadRequired()) {
        LOGGER.info("Git data upload needs to finish before remote settings can be retrieved");
        gitDataUploader
            .startOrObserveGitDataUpload()
            .get(config.getCiVisibilityGitUploadTimeoutMillis(), TimeUnit.MILLISECONDS);

        return configurationApi.getSettings(tracerEnvironment);
      } else {
        return settings;
      }

    } catch (Exception e) {
      LOGGER.warn(
          "Could not obtain CI Visibility settings, will default to disabled code coverage and tests skipping");
      LOGGER.debug("Error while obtaining CI Visibility settings", e);
      return CiVisibilitySettings.DEFAULT;
    }
  }

  private boolean isItrEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isTestsSkippingEnabled() && config.isCiVisibilityItrEnabled();
  }

  private boolean isCodeCoverageEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isCodeCoverageEnabled()
        && config.isCiVisibilityCodeCoverageEnabled();
  }

  private boolean isFlakyTestRetriesEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isFlakyTestRetriesEnabled()
        && config.isCiVisibilityFlakyRetryEnabled();
  }

  private Map<String, String> getPropertiesPropagatedToChildProcess(
      boolean codeCoverageEnabled, boolean itrEnabled, boolean flakyTestRetriesEnabled) {
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

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ENABLED),
        Boolean.toString(flakyTestRetriesEnabled));

    // explicitly disable build instrumentation in child processes,
    // because some projects run "embedded" Maven/Gradle builds as part of their integration tests,
    // and we don't want to show those as if they were regular build executions
    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED),
        Boolean.toString(false));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_INJECTED_TRACER_VERSION),
        TracerVersion.TRACER_VERSION);

    return propagatedSystemProperties;
  }

  private Map<String, Collection<TestIdentifier>> getSkippableTestsByModuleName(
      Path repositoryRoot, TracerEnvironment tracerEnvironment) {
    try {
      // ensure git data upload is finished before asking for tests
      gitDataUploader
          .startOrObserveGitDataUpload()
          .get(config.getCiVisibilityGitUploadTimeoutMillis(), TimeUnit.MILLISECONDS);

      Collection<TestIdentifier> skippableTests =
          configurationApi.getSkippableTests(tracerEnvironment);
      LOGGER.info(
          "Received {} skippable tests in total for {}", skippableTests.size(), repositoryRoot);

      return (Map<String, Collection<TestIdentifier>>)
          groupTestsByModule(tracerEnvironment, skippableTests);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted while waiting for git data upload", e);
      return Collections.emptyMap();

    } catch (Exception e) {
      LOGGER.error("Could not obtain list of skippable tests, will proceed without skipping", e);
      return Collections.emptyMap();
    }
  }

  private static Map<String, ? extends Collection<TestIdentifier>> groupTestsByModule(
      TracerEnvironment tracerEnvironment, Collection<TestIdentifier> tests) {
    Configurations configurations = tracerEnvironment.getConfigurations();
    String configurationsBundle = configurations.getTestBundle();
    String defaultBundle = configurationsBundle != null ? configurationsBundle : "";
    return tests.stream()
        .collect(
            Collectors.groupingBy(
                t ->
                    t.getConfigurations() != null && t.getConfigurations().getTestBundle() != null
                        ? t.getConfigurations().getTestBundle()
                        : defaultBundle));
  }

  private Collection<TestIdentifier> getFlakyTests(TracerEnvironment tracerEnvironment) {
    try {
      Collection<TestIdentifier> flakyTests = configurationApi.getFlakyTests(tracerEnvironment);
      LOGGER.info("Received {} flaky tests in total for {}", flakyTests.size(), repositoryRoot);
      return flakyTests;

    } catch (Exception e) {
      LOGGER.error(
          "Could not obtain list of flaky tests, flaky test retries will not be available", e);
      return Collections.emptyList();
    }
  }

  private List<String> getCoverageEnabledPackages(boolean codeCoverageEnabled) {
    if (!codeCoverageEnabled) {
      return Collections.emptyList();
    }

    List<String> includedPackages = config.getCiVisibilityCodeCoverageIncludes();
    if (includedPackages != null && !includedPackages.isEmpty()) {
      return includedPackages;
    }

    RepoIndex repoIndex = repoIndexProvider.getIndex();
    List<String> packages = new ArrayList<>(repoIndex.getRootPackages());
    List<String> excludedPackages = config.getCiVisibilityCodeCoverageExcludes();
    if (excludedPackages != null && !excludedPackages.isEmpty()) {
      removeMatchingPackages(packages, excludedPackages);
    }
    return packages;
  }

  private static void removeMatchingPackages(List<String> packages, List<String> excludedPackages) {
    List<String> excludedPrefixes =
        excludedPackages.stream()
            .map(ModuleExecutionSettingsFactoryImpl::trimTrailingAsterisk)
            .collect(Collectors.toList());

    Iterator<String> packagesIterator = packages.iterator();
    while (packagesIterator.hasNext()) {
      String p = packagesIterator.next();

      for (String excludedPrefix : excludedPrefixes) {
        if (p.startsWith(excludedPrefix)) {
          packagesIterator.remove();
          break;
        }
      }
    }
  }

  private static String trimTrailingAsterisk(String s) {
    return s.endsWith("*") ? s.substring(0, s.length() - 1) : s;
  }
}
