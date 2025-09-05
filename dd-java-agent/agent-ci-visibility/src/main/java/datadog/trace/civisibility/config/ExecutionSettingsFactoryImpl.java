package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.diff.Diff;
import datadog.trace.civisibility.diff.LineDiff;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.git.tree.GitRepoUnshallow;
import datadog.trace.util.Strings;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionSettingsFactoryImpl implements ExecutionSettingsFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionSettingsFactoryImpl.class);

  private static final String TEST_CONFIGURATION_TAG_PREFIX = "test.configuration.";

  private static final ThreadFactory THREAD_FACTORY = r -> new Thread(null, r, "dd-ci-vis-config");

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
  @Nullable private final String repositoryRoot;

  public ExecutionSettingsFactoryImpl(
      Config config,
      ConfigurationApi configurationApi,
      GitClient gitClient,
      GitRepoUnshallow gitRepoUnshallow,
      GitDataUploader gitDataUploader,
      PullRequestInfo pullRequestInfo,
      @Nullable String repositoryRoot) {
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
    TracerEnvironment tracerEnvironment = buildTracerEnvironment(jvmInfo, null);
    return create(tracerEnvironment);
  }

  @Override
  public ExecutionSettings create(@Nonnull JvmInfo jvmInfo, @Nullable String moduleName) {
    TracerEnvironment tracerEnvironment = buildTracerEnvironment(jvmInfo, moduleName);
    Map<String, ExecutionSettings> settingsByModule = create(tracerEnvironment);
    ExecutionSettings settings = settingsByModule.get(moduleName);
    return settings != null ? settings : settingsByModule.get(DEFAULT_SETTINGS);
  }

  private TracerEnvironment buildTracerEnvironment(JvmInfo jvmInfo, @Nullable String moduleName) {
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
        .tag(gitInfo.getTag())
        .sha(gitInfo.getCommit().getSha())
        .commitMessage(gitInfo.getCommit().getFullMessage())
        .osPlatform(wellKnownTags.getOsPlatform().toString())
        .osArchitecture(wellKnownTags.getOsArch().toString())
        .osVersion(wellKnownTags.getOsVersion().toString())
        .runtimeName(jvmInfo.getName())
        .runtimeVersion(jvmInfo.getVersion())
        .runtimeVendor(jvmInfo.getVendor())
        .testBundle(moduleName)
        .build();
  }

  @Nonnull
  private Map<String, ExecutionSettings> create(TracerEnvironment tracerEnvironment) {
    CiVisibilitySettings settings = getCiVisibilitySettings(tracerEnvironment);
    ExecutorService settingsExecutor = Executors.newCachedThreadPool(THREAD_FACTORY);
    try {
      return doCreate(tracerEnvironment, settings, settingsExecutor);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted while creating execution settings");
      return Collections.singletonMap(DEFAULT_SETTINGS, ExecutionSettings.EMPTY);

    } catch (ExecutionException e) {
      LOGGER.error("Error while creating execution settings", e);
      return Collections.singletonMap(DEFAULT_SETTINGS, ExecutionSettings.EMPTY);

    } finally {
      settingsExecutor.shutdownNow();
    }
  }

  @Nonnull
  private Map<String, ExecutionSettings> doCreate(
      TracerEnvironment tracerEnvironment, CiVisibilitySettings settings, ExecutorService executor)
      throws InterruptedException, ExecutionException {
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
    boolean impactedTestsEnabled =
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
    boolean codeCoverageReportUpload =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isCoverageReportUploadEnabled,
            Config::isCiVisibilityCodeCoverageReportUploadEnabled);
    boolean failedTestReplayEnabled =
        isFeatureEnabled(
            settings,
            CiVisibilitySettings::isFailedTestReplayEnabled,
            Config::isCiVisibilityFailedTestReplayEnabled);

    TestManagementSettings testManagementSettings = getTestManagementSettings(settings);

    LOGGER.info(
        "CI Visibility settings ({}, {}/{}/{}):\n"
            + "Intelligent Test Runner - {},\n"
            + "Per-test code coverage - {},\n"
            + "Tests skipping - {},\n"
            + "Early flakiness detection - {},\n"
            + "Impacted tests detection - {},\n"
            + "Known tests marking - {},\n"
            + "Auto test retries - {},\n"
            + "Test Management - {},\n"
            + "Code coverage report upload - {},\n"
            + "Failed Test Replay - {}",
        repositoryRoot,
        tracerEnvironment.getConfigurations().getRuntimeName(),
        tracerEnvironment.getConfigurations().getRuntimeVersion(),
        tracerEnvironment.getConfigurations().getRuntimeVendor(),
        itrEnabled,
        codeCoverageEnabled,
        testSkippingEnabled,
        earlyFlakeDetectionEnabled,
        impactedTestsEnabled,
        knownTestsRequest,
        flakyTestRetriesEnabled,
        testManagementSettings.isEnabled(),
        codeCoverageReportUpload,
        failedTestReplayEnabled);

    Future<SkippableTests> skippableTestsFuture =
        executor.submit(() -> getSkippableTests(tracerEnvironment, itrEnabled));
    Future<Map<String, Collection<TestFQN>>> flakyTestsFuture =
        executor.submit(() -> getFlakyTestsByModule(tracerEnvironment, flakyTestRetriesEnabled));
    Future<Map<String, Collection<TestFQN>>> knownTestsFuture =
        executor.submit(() -> getKnownTestsByModule(tracerEnvironment, knownTestsRequest));
    Future<Map<TestSetting, Map<String, Collection<TestFQN>>>> testManagementTestsFuture =
        executor.submit(
            () ->
                getTestManagementTestsByModule(
                    tracerEnvironment, testManagementSettings.isEnabled()));
    Future<Diff> pullRequestDiffFuture =
        executor.submit(
            () -> getPullRequestDiff(impactedTestsEnabled, settings.getDefaultBranch()));

    SkippableTests skippableTests = skippableTestsFuture.get();
    Map<String, Collection<TestFQN>> flakyTestsByModule = flakyTestsFuture.get();
    Map<String, Collection<TestFQN>> knownTestsByModule = knownTestsFuture.get();

    Map<TestSetting, Map<String, Collection<TestFQN>>> testManagementTestsByModule =
        testManagementTestsFuture.get();
    Map<String, Collection<TestFQN>> quarantinedTestsByModule =
        testManagementTestsByModule.getOrDefault(TestSetting.QUARANTINED, Collections.emptyMap());
    Map<String, Collection<TestFQN>> disabledTestsByModule =
        testManagementTestsByModule.getOrDefault(TestSetting.DISABLED, Collections.emptyMap());
    Map<String, Collection<TestFQN>> attemptToFixTestsByModule =
        testManagementTestsByModule.getOrDefault(
            TestSetting.ATTEMPT_TO_FIX, Collections.emptyMap());

    Diff pullRequestDiff = pullRequestDiffFuture.get();

    Map<String, ExecutionSettings> settingsByModule = new HashMap<>();
    Set<String> moduleNames =
        getModuleNames(
            skippableTests,
            flakyTestsByModule,
            knownTestsByModule,
            quarantinedTestsByModule,
            disabledTestsByModule,
            attemptToFixTestsByModule);

    for (String moduleName : moduleNames) {
      settingsByModule.put(
          moduleName,
          new ExecutionSettings(
              itrEnabled,
              codeCoverageEnabled,
              testSkippingEnabled,
              flakyTestRetriesEnabled,
              impactedTestsEnabled,
              codeCoverageReportUpload,
              failedTestReplayEnabled,
              earlyFlakeDetectionEnabled
                  ? settings.getEarlyFlakeDetectionSettings()
                  : EarlyFlakeDetectionSettings.DEFAULT,
              testManagementSettings,
              skippableTests.getCorrelationId(),
              skippableTests
                  .getIdentifiersByModule()
                  .getOrDefault(moduleName, Collections.emptyMap()),
              skippableTests.getCoveredLinesByRelativeSourcePath(),
              flakyTestsByModule != null
                  ? flakyTestsByModule.getOrDefault(moduleName, Collections.emptyList())
                  : null,
              knownTestsByModule != null ? knownTestsByModule.get(moduleName) : null,
              quarantinedTestsByModule.getOrDefault(moduleName, Collections.emptyList()),
              disabledTestsByModule.getOrDefault(moduleName, Collections.emptyList()),
              attemptToFixTestsByModule.getOrDefault(moduleName, Collections.emptyList()),
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

  @Nonnull
  private TestManagementSettings getTestManagementSettings(CiVisibilitySettings settings) {
    boolean testManagementEnabled =
        isFeatureEnabled(
            settings,
            s -> s.getTestManagementSettings().isEnabled(),
            Config::isCiVisibilityTestManagementEnabled);

    if (!testManagementEnabled) {
      return TestManagementSettings.DEFAULT;
    }

    Integer retries = config.getCiVisibilityTestManagementAttemptToFixRetries();
    if (retries != null) {
      return new TestManagementSettings(true, retries);
    }

    return settings.getTestManagementSettings();
  }

  @Nonnull
  private SkippableTests getSkippableTests(
      TracerEnvironment tracerEnvironment, boolean itrEnabled) {
    if (!itrEnabled || repositoryRoot == null) {
      return SkippableTests.EMPTY;
    }
    try {
      // ensure git data upload is finished before asking for tests
      gitDataUploader
          .startOrObserveGitDataUpload()
          .get(config.getCiVisibilityGitUploadTimeoutMillis(), TimeUnit.MILLISECONDS);

      SkippableTests skippableTests = configurationApi.getSkippableTests(tracerEnvironment);

      if (LOGGER.isDebugEnabled()) {
        int totalSkippableTests =
            skippableTests.getIdentifiersByModule().values().stream()
                .filter(Objects::nonNull)
                .mapToInt(Map::size)
                .sum();
        LOGGER.debug(
            "Received {} skippable tests in total for {}",
            totalSkippableTests,
            Paths.get(repositoryRoot));
      }

      return skippableTests;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted while waiting for git data upload", e);
      return SkippableTests.EMPTY;
    } catch (Exception e) {
      LOGGER.error("Could not obtain list of skippable tests, will proceed without skipping", e);
      return SkippableTests.EMPTY;
    }
  }

  @Nullable
  private Map<String, Collection<TestFQN>> getFlakyTestsByModule(
      TracerEnvironment tracerEnvironment, boolean flakyTestRetriesEnabled) {
    if (!(flakyTestRetriesEnabled && config.isCiVisibilityFlakyRetryOnlyKnownFlakes())
        && !CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(config.getCiVisibilityTestOrder())) {
      return null;
    }
    try {
      return configurationApi.getFlakyTestsByModule(tracerEnvironment);
    } catch (Exception e) {
      LOGGER.error("Could not obtain list of flaky tests", e);
      return null;
    }
  }

  @Nullable
  private Map<String, Collection<TestFQN>> getKnownTestsByModule(
      TracerEnvironment tracerEnvironment, boolean knownTestsRequest) {
    if (!knownTestsRequest) {
      return null;
    }
    try {
      return configurationApi.getKnownTestsByModule(tracerEnvironment);

    } catch (Exception e) {
      LOGGER.error("Could not obtain list of known tests", e);
      return null;
    }
  }

  @Nullable
  private Map<TestSetting, Map<String, Collection<TestFQN>>> getTestManagementTestsByModule(
      TracerEnvironment tracerEnvironment, boolean testManagementTestsRequest) {
    if (!testManagementTestsRequest) {
      return Collections.emptyMap();
    }
    try {
      if (Strings.isNotBlank(pullRequestInfo.getHeadCommit().getSha())
          && Strings.isNotBlank(pullRequestInfo.getHeadCommit().getFullMessage())) {
        return configurationApi.getTestManagementTestsByModule(
            tracerEnvironment,
            pullRequestInfo.getHeadCommit().getSha(),
            pullRequestInfo.getHeadCommit().getFullMessage());
      } else {
        return configurationApi.getTestManagementTestsByModule(
            tracerEnvironment, tracerEnvironment.getSha(), tracerEnvironment.getCommitMessage());
      }
    } catch (Exception e) {
      LOGGER.error("Could not obtain list of test management tests", e);
      return Collections.emptyMap();
    }
  }

  @Nonnull
  private Diff getPullRequestDiff(boolean impactedTestsDetectionEnabled, String defaultBranch) {
    if (!impactedTestsDetectionEnabled) {
      return LineDiff.EMPTY;
    }

    try {
      if (repositoryRoot != null) {
        // ensure repo is not shallow before attempting to get git diff
        gitRepoUnshallow.unshallow();

        String baseCommitSha = pullRequestInfo.getBaseBranchSha();
        if (baseCommitSha == null && pullRequestInfo.getBaseBranchHeadSha() != null) {
          baseCommitSha =
              gitClient.getMergeBase(
                  pullRequestInfo.getBaseBranchHeadSha(), pullRequestInfo.getHeadCommit().getSha());
        }

        if (baseCommitSha == null) {
          baseCommitSha =
              gitClient.getBaseCommitSha(pullRequestInfo.getBaseBranch(), defaultBranch);
        }

        Diff diff = gitClient.getGitDiff(baseCommitSha, pullRequestInfo.getHeadCommit().getSha());
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

    return LineDiff.EMPTY;
  }

  @Nonnull
  private static Set<String> getModuleNames(
      @Nonnull SkippableTests skippableTests,
      @Nullable Map<String, Collection<TestFQN>> flakyTestsByModule,
      @Nullable Map<String, Collection<TestFQN>> knownTestsByModule,
      @Nonnull Map<String, Collection<TestFQN>> quarantinedTestsByModule,
      @Nonnull Map<String, Collection<TestFQN>> disabledTestsByModule,
      @Nonnull Map<String, Collection<TestFQN>> attemptToFixTestsByModule) {
    Set<String> moduleNames = new HashSet<>(Collections.singleton(DEFAULT_SETTINGS));
    moduleNames.addAll(skippableTests.getIdentifiersByModule().keySet());
    if (flakyTestsByModule != null) {
      moduleNames.addAll(flakyTestsByModule.keySet());
    }
    if (knownTestsByModule != null) {
      moduleNames.addAll(knownTestsByModule.keySet());
    }
    moduleNames.addAll(quarantinedTestsByModule.keySet());
    moduleNames.addAll(disabledTestsByModule.keySet());
    moduleNames.addAll(attemptToFixTestsByModule.keySet());
    return moduleNames;
  }
}
