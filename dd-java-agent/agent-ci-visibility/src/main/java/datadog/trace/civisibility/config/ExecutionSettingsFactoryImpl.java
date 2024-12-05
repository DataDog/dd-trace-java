package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionSettingsFactoryImpl implements ExecutionSettingsFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionSettingsFactoryImpl.class);
  private static final String TEST_CONFIGURATION_TAG_PREFIX = "test.configuration.";

  /**
   * A workaround for bulk-requesting module settings. For any module that has no settings that are
   * exclusive to it (i.e. that has no skippable/flaky/known tests), the settings will be under this
   * key in the resulting map.
   */
  static final String DEFAULT_SETTINGS = "<DEFAULT>";

  private final Config config;
  private final ConfigurationApi configurationApi;
  private final GitDataUploader gitDataUploader;
  private final String repositoryRoot;

  public ExecutionSettingsFactoryImpl(
      Config config,
      ConfigurationApi configurationApi,
      GitDataUploader gitDataUploader,
      String repositoryRoot) {
    this.config = config;
    this.configurationApi = configurationApi;
    this.gitDataUploader = gitDataUploader;
    this.repositoryRoot = repositoryRoot;
  }

  /** @return Executions settings by module name */
  public Map<String, ExecutionSettings> create(@Nonnull JvmInfo jvmInfo) {
    TracerEnvironment tracerEnvironment = buildTracerEnvironment(repositoryRoot, jvmInfo, null);
    return create(tracerEnvironment);
  }

  @Override
  public ExecutionSettings create(@Nonnull JvmInfo jvmInfo, @Nullable String moduleName) {
    TracerEnvironment tracerEnvironment =
        buildTracerEnvironment(repositoryRoot, jvmInfo, moduleName);
    Map<String, ExecutionSettings> settingsByModule = create(tracerEnvironment);
    ExecutionSettings settings = settingsByModule.get(moduleName);
    return settings != null ? settings : settingsByModule.get(DEFAULT_SETTINGS);
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

    CiVisibilityWellKnownTags wellKnownTags = config.getCiVisibilityWellKnownTags();
    return builder
        .service(config.getServiceName())
        .env(config.getEnv())
        .repositoryUrl(gitInfo.getRepositoryURL())
        .branch(gitInfo.getBranch())
        .sha(gitInfo.getCommit().getSha())
        .osPlatform(wellKnownTags.getOsPlatform().toString())
        .osArchitecture(wellKnownTags.getOsArch().toString())
        .osVersion(wellKnownTags.getOsVersion().toString())
        .runtimeName(jvmInfo.getName())
        .runtimeVersion(jvmInfo.getVersion())
        .runtimeVendor(jvmInfo.getVendor())
        .testBundle(moduleName)
        .build();
  }

  private @NotNull Map<String, ExecutionSettings> create(TracerEnvironment tracerEnvironment) {
    CiVisibilitySettings ciVisibilitySettings = getCiVisibilitySettings(tracerEnvironment);

    boolean itrEnabled = isItrEnabled(ciVisibilitySettings);
    boolean codeCoverageEnabled = isCodeCoverageEnabled(ciVisibilitySettings);
    boolean testSkippingEnabled = isTestSkippingEnabled(ciVisibilitySettings);
    boolean flakyTestRetriesEnabled = isFlakyTestRetriesEnabled(ciVisibilitySettings);
    boolean earlyFlakeDetectionEnabled = isEarlyFlakeDetectionEnabled(ciVisibilitySettings);

    LOGGER.info(
        "CI Visibility settings ({}, {}/{}/{}):\n"
            + "Intelligent Test Runner - {},\n"
            + "Per-test code coverage - {},\n"
            + "Tests skipping - {},\n"
            + "Early flakiness detection - {},\n"
            + "Auto test retries - {}",
        repositoryRoot,
        tracerEnvironment.getConfigurations().getRuntimeName(),
        tracerEnvironment.getConfigurations().getRuntimeVersion(),
        tracerEnvironment.getConfigurations().getRuntimeVendor(),
        itrEnabled,
        codeCoverageEnabled,
        testSkippingEnabled,
        earlyFlakeDetectionEnabled,
        flakyTestRetriesEnabled);

    String itrCorrelationId = null;
    Map<String, Map<TestIdentifier, TestMetadata>> skippableTestIdentifiers =
        Collections.emptyMap();
    Map<String, BitSet> skippableTestsCoverage = null;
    if (itrEnabled && repositoryRoot != null) {
      SkippableTests skippableTests =
          getSkippableTests(Paths.get(repositoryRoot), tracerEnvironment);
      if (skippableTests != null) {
        itrCorrelationId = skippableTests.getCorrelationId();
        skippableTestIdentifiers = skippableTests.getIdentifiersByModule();
        skippableTestsCoverage = skippableTests.getCoveredLinesByRelativeSourcePath();
      }
    }

    Map<String, Collection<TestIdentifier>> flakyTestsByModule =
        flakyTestRetriesEnabled && config.isCiVisibilityFlakyRetryOnlyKnownFlakes()
                || CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(
                    config.getCiVisibilityTestOrder())
            ? getFlakyTestsByModule(tracerEnvironment)
            : null;

    Map<String, Collection<TestIdentifier>> knownTestsByModule =
        earlyFlakeDetectionEnabled
                || CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(
                    config.getCiVisibilityTestOrder())
            ? getKnownTestsByModule(tracerEnvironment)
            : null;

    Set<String> moduleNames = new HashSet<>(Collections.singleton(DEFAULT_SETTINGS));
    moduleNames.addAll(skippableTestIdentifiers.keySet());
    if (flakyTestsByModule != null) {
      moduleNames.addAll(flakyTestsByModule.keySet());
    }
    if (knownTestsByModule != null) {
      moduleNames.addAll(knownTestsByModule.keySet());
    }

    Map<String, ExecutionSettings> settingsByModule = new HashMap<>();
    for (String moduleName : moduleNames) {
      settingsByModule.put(
          moduleName,
          new ExecutionSettings(
              itrEnabled,
              codeCoverageEnabled,
              testSkippingEnabled,
              flakyTestRetriesEnabled,
              // knownTests being null covers the following cases:
              //  - early flake detection is disabled in remote settings
              //  - early flake detection is disabled via local config killswitch
              //  - the list of known tests could not be obtained
              knownTestsByModule != null
                  ? ciVisibilitySettings.getEarlyFlakeDetectionSettings()
                  : EarlyFlakeDetectionSettings.DEFAULT,
              itrCorrelationId,
              skippableTestIdentifiers.getOrDefault(moduleName, Collections.emptyMap()),
              skippableTestsCoverage,
              flakyTestsByModule != null
                  ? flakyTestsByModule.getOrDefault(moduleName, Collections.emptyList())
                  : null,
              knownTestsByModule != null ? knownTestsByModule.get(moduleName) : null));
    }
    return settingsByModule;
  }

  private CiVisibilitySettings getCiVisibilitySettings(TracerEnvironment tracerEnvironment) {
    try {
      CiVisibilitySettings settings = configurationApi.getSettings(tracerEnvironment);
      if (settings.isGitUploadRequired()) {
        LOGGER.debug("Git data upload needs to finish before remote settings can be retrieved");
        gitDataUploader
            .startOrObserveGitDataUpload()
            .get(config.getCiVisibilityGitUploadTimeoutMillis(), TimeUnit.MILLISECONDS);

        return configurationApi.getSettings(tracerEnvironment);
      } else {
        return settings;
      }

    } catch (Exception e) {
      LOGGER.warn("Error while obtaining CI Visibility settings", e);
      return CiVisibilitySettings.DEFAULT;
    }
  }

  private boolean isItrEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isItrEnabled() && config.isCiVisibilityItrEnabled();
  }

  private boolean isTestSkippingEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isTestsSkippingEnabled()
        && config.isCiVisibilityTestSkippingEnabled();
  }

  private boolean isCodeCoverageEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isCodeCoverageEnabled()
        && config.isCiVisibilityCodeCoverageEnabled();
  }

  private boolean isFlakyTestRetriesEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.isFlakyTestRetriesEnabled()
        && config.isCiVisibilityFlakyRetryEnabled();
  }

  private boolean isEarlyFlakeDetectionEnabled(CiVisibilitySettings ciVisibilitySettings) {
    return ciVisibilitySettings.getEarlyFlakeDetectionSettings().isEnabled()
        && config.isCiVisibilityEarlyFlakeDetectionEnabled();
  }

  @Nullable
  private SkippableTests getSkippableTests(
      Path repositoryRoot, TracerEnvironment tracerEnvironment) {
    try {
      // ensure git data upload is finished before asking for tests
      gitDataUploader
          .startOrObserveGitDataUpload()
          .get(config.getCiVisibilityGitUploadTimeoutMillis(), TimeUnit.MILLISECONDS);

      SkippableTests skippableTests = configurationApi.getSkippableTests(tracerEnvironment);
      LOGGER.debug(
          "Received {} skippable tests in total for {}",
          skippableTests.getIdentifiersByModule().size(),
          repositoryRoot);

      return skippableTests;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted while waiting for git data upload", e);
      return null;

    } catch (Exception e) {
      LOGGER.error("Could not obtain list of skippable tests, will proceed without skipping", e);
      return null;
    }
  }

  private Map<String, Collection<TestIdentifier>> getFlakyTestsByModule(
      TracerEnvironment tracerEnvironment) {
    try {
      return configurationApi.getFlakyTestsByModule(tracerEnvironment);

    } catch (Exception e) {
      LOGGER.error(
          "Could not obtain list of flaky tests, flaky test retries will not be available", e);
      return Collections.emptyMap();
    }
  }

  private Map<String, Collection<TestIdentifier>> getKnownTestsByModule(
      TracerEnvironment tracerEnvironment) {
    try {
      return configurationApi.getKnownTestsByModule(tracerEnvironment);

    } catch (Exception e) {
      LOGGER.error(
          "Could not obtain list of known tests, early flakiness detection will not be available",
          e);
      return null;
    }
  }
}
