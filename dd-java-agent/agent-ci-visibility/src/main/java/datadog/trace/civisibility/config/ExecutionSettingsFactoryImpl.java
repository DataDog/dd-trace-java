package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.diff.Diff;
import datadog.trace.civisibility.diff.FileDiff;
import datadog.trace.civisibility.diff.LineDiff;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.git.tree.GitRepoUnshallow;
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
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
  private final GitClient gitClient;
  private final GitRepoUnshallow gitRepoUnshallow;
  private final GitDataUploader gitDataUploader;
  private final PullRequestInfo pullRequestInfo;
  private final String repositoryRoot;

  public ExecutionSettingsFactoryImpl(
      Config config,
      ConfigurationApi configurationApi,
      GitClient gitClient,
      GitRepoUnshallow gitRepoUnshallow,
      GitDataUploader gitDataUploader,
      PullRequestInfo pullRequestInfo,
      String repositoryRoot) {
    this.config = config;
    this.configurationApi = configurationApi;
    this.gitClient = gitClient;
    this.gitRepoUnshallow = gitRepoUnshallow;
    this.gitDataUploader = gitDataUploader;
    this.pullRequestInfo = pullRequestInfo;
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

  private @Nonnull Map<String, ExecutionSettings> create(TracerEnvironment tracerEnvironment) {
    CiVisibilitySettings settings = getCiVisibilitySettings(tracerEnvironment);

    boolean itrEnabled =
        isFeatureEnabled(
            settings, CiVisibilitySettings::isItrEnabled, Config::isCiVisibilityItrEnabled);
    boolean codeCoverageEnabled =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isCodeCoverageEnabled,
            Config::isCiVisibilityCodeCoverageEnabled);
    boolean testSkippingEnabled =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isTestsSkippingEnabled,
            Config::isCiVisibilityTestSkippingEnabled);
    boolean flakyTestRetriesEnabled =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isFlakyTestRetriesEnabled,
            Config::isCiVisibilityFlakyRetryEnabled);
    boolean impactedTestsDetectionEnabled =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isImpactedTestsDetectionEnabled,
            Config::isCiVisibilityImpactedTestsDetectionEnabled);
    boolean earlyFlakeDetectionEnabled =
        isFeatureEnabled(
            settings,
            s -> s.getEarlyFlakeDetectionSettings().isEnabled(),
            Config::isCiVisibilityEarlyFlakeDetectionEnabled);
    boolean knownTestsRequest =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isKnownTestsEnabled,
            Config::isCiVisibilityKnownTestsRequestEnabled);

    LOGGER.info(
        "CI Visibility settings ({}, {}/{}/{}):\n"
            + "Intelligent Test Runner - {},\n"
            + "Per-test code coverage - {},\n"
            + "Tests skipping - {},\n"
            + "Early flakiness detection - {},\n"
            + "Impacted tests detection - {},\n"
            + "Known tests marking - {},\n"
            + "Auto test retries - {}",
        repositoryRoot,
        tracerEnvironment.getConfigurations().getRuntimeName(),
        tracerEnvironment.getConfigurations().getRuntimeVersion(),
        tracerEnvironment.getConfigurations().getRuntimeVendor(),
        itrEnabled,
        codeCoverageEnabled,
        testSkippingEnabled,
        earlyFlakeDetectionEnabled,
        impactedTestsDetectionEnabled,
        knownTestsRequest,
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
        knownTestsRequest ? getKnownTestsByModule(tracerEnvironment) : null;

    Set<String> moduleNames = new HashSet<>(Collections.singleton(DEFAULT_SETTINGS));
    moduleNames.addAll(skippableTestIdentifiers.keySet());
    if (flakyTestsByModule != null) {
      moduleNames.addAll(flakyTestsByModule.keySet());
    }
    if (knownTestsByModule != null) {
      moduleNames.addAll(knownTestsByModule.keySet());
    }

    Diff pullRequestDiff = getPullRequestDiff(impactedTestsDetectionEnabled, tracerEnvironment);

    Map<String, ExecutionSettings> settingsByModule = new HashMap<>();
    for (String moduleName : moduleNames) {
      settingsByModule.put(
          moduleName,
          new ExecutionSettings(
              itrEnabled,
              codeCoverageEnabled,
              testSkippingEnabled,
              flakyTestRetriesEnabled,
              impactedTestsDetectionEnabled,
              earlyFlakeDetectionEnabled
                  ? settings.getEarlyFlakeDetectionSettings()
                  : EarlyFlakeDetectionSettings.DEFAULT,
              itrCorrelationId,
              skippableTestIdentifiers.getOrDefault(moduleName, Collections.emptyMap()),
              skippableTestsCoverage,
              flakyTestsByModule != null
                  ? flakyTestsByModule.getOrDefault(moduleName, Collections.emptyList())
                  : null,
              knownTestsByModule != null ? knownTestsByModule.get(moduleName) : null,
              pullRequestDiff));
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

  private boolean isFeatureEnabled(
      CiVisibilitySettings ciVisibilitySettings,
      Function<CiVisibilitySettings, Boolean> remoteSetting,
      Function<Config, Boolean> killSwitch) {
    return remoteSetting.apply(ciVisibilitySettings) && killSwitch.apply(config);
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
      LOGGER.error("Could not obtain list of flaky tests", e);
      return Collections.emptyMap();
    }
  }

  private Map<String, Collection<TestIdentifier>> getKnownTestsByModule(
      TracerEnvironment tracerEnvironment) {
    try {
      return configurationApi.getKnownTestsByModule(tracerEnvironment);

    } catch (Exception e) {
      LOGGER.error("Could not obtain list of known tests", e);
      return null;
    }
  }

  @Nonnull
  private Diff getPullRequestDiff(
      boolean impactedTestsDetectionEnabled, TracerEnvironment tracerEnvironment) {
    if (!impactedTestsDetectionEnabled) {
      return LineDiff.EMPTY;
    }

    try {
      if (repositoryRoot != null) {
        // ensure repo is not shallow before attempting to get git diff
        gitRepoUnshallow.unshallow();
        Diff diff =
            gitClient.getGitDiff(
                pullRequestInfo.getPullRequestBaseBranchSha(),
                pullRequestInfo.getGitCommitHeadSha());
        if (diff != null) {
          return diff;
        }
      }

    } catch (InterruptedException e) {
      LOGGER.error("Interrupted while getting git diff for PR: {}", pullRequestInfo, e);
      Thread.currentThread().interrupt();

    } catch (Exception e) {
      LOGGER.error("Could not get git diff for PR: {}", pullRequestInfo, e);
    }

    try {
      ChangedFiles changedFiles = configurationApi.getChangedFiles(tracerEnvironment);

      // attempting to use base SHA returned by the backend to calculate git diff
      if (repositoryRoot != null) {
        // ensure repo is not shallow before attempting to get git diff
        gitRepoUnshallow.unshallow();
        Diff diff = gitClient.getGitDiff(changedFiles.getBaseSha(), tracerEnvironment.getSha());
        if (diff != null) {
          return diff;
        }
      }

      // falling back to file-level granularity
      return new FileDiff(changedFiles.getFiles());

    } catch (InterruptedException e) {
      LOGGER.error("Interrupted while getting git diff for: {}", tracerEnvironment, e);
      Thread.currentThread().interrupt();

    } catch (Exception e) {
      LOGGER.error("Could not get git diff for: {}", tracerEnvironment, e);
    }

    return LineDiff.EMPTY;
  }
}
