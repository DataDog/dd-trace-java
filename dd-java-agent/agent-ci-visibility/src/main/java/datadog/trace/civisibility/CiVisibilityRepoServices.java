package datadog.trace.civisibility;

import datadog.communication.BackendApi;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CITagsProvider;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.codeowners.NoCodeowners;
import datadog.trace.civisibility.config.ConfigurationApi;
import datadog.trace.civisibility.config.ConfigurationApiImpl;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.ExecutionSettingsFactory;
import datadog.trace.civisibility.config.ExecutionSettingsFactoryImpl;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.MultiModuleExecutionSettingsFactory;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataApi;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.git.tree.GitDataUploaderImpl;
import datadog.trace.civisibility.git.tree.GitRepoUnshallow;
import datadog.trace.civisibility.ipc.ExecutionSettingsRequest;
import datadog.trace.civisibility.ipc.ExecutionSettingsResponse;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.BestEffortSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.NoOpSourcePathResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.source.index.RepoIndexSourcePathResolver;
import datadog.trace.util.Strings;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Services that need repository root location to be instantiated. The scope is session. */
public class CiVisibilityRepoServices {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilityRepoServices.class);

  @Nullable final String repoRoot;
  final String moduleName;
  final Provider ciProvider;
  final Map<String, String> ciTags;

  final GitDataUploader gitDataUploader;
  final RepoIndexProvider repoIndexProvider;
  final Codeowners codeowners;
  final SourcePathResolver sourcePathResolver;
  final ExecutionSettingsFactory executionSettingsFactory;

  CiVisibilityRepoServices(CiVisibilityServices services, Path path) {
    CIProviderInfo ciProviderInfo = services.ciProviderInfoFactory.createCIProviderInfo(path);
    ciProvider = ciProviderInfo.getProvider();
    CIInfo ciInfo = ciProviderInfo.buildCIInfo();

    repoRoot = getRepoRoot(ciInfo, services.gitClientFactory);
    moduleName = getModuleName(services.config, repoRoot, path);

    GitClient gitClient = services.gitClientFactory.create(repoRoot);
    GitRepoUnshallow gitRepoUnshallow = new GitRepoUnshallow(services.config, gitClient);
    PullRequestInfo pullRequestInfo =
        buildPullRequestInfo(
            services.config, services.environment, ciProviderInfo, gitClient, gitRepoUnshallow);

    if (!pullRequestInfo.isEmpty()) {
      LOGGER.info("PR detected: {}", pullRequestInfo);
    }

    ciTags = new CITagsProvider().getCiTags(ciInfo, pullRequestInfo);

    gitDataUploader =
        buildGitDataUploader(
            services.config,
            services.metricCollector,
            services.gitInfoProvider,
            gitClient,
            gitRepoUnshallow,
            services.backendApi,
            repoRoot);
    repoIndexProvider = services.repoIndexProviderFactory.create(repoRoot);
    codeowners = buildCodeowners(repoRoot);
    sourcePathResolver = buildSourcePathResolver(repoRoot, repoIndexProvider);

    if (services.processHierarchy.isChild()) {
      executionSettingsFactory = buildExecutionSettingsFetcher(services.signalClientFactory);
    } else {
      executionSettingsFactory =
          buildExecutionSettingsFactory(
              services.processHierarchy,
              services.config,
              services.metricCollector,
              services.backendApi,
              gitClient,
              gitRepoUnshallow,
              gitDataUploader,
              pullRequestInfo,
              repoRoot);
    }
  }

  @Nonnull
  static PullRequestInfo buildPullRequestInfo(
      Config config,
      CiEnvironment environment,
      CIProviderInfo ciProviderInfo,
      GitClient gitClient,
      GitRepoUnshallow gitRepoUnshallow) {
    PullRequestInfo userInfo = buildUserPullRequestInfo(config, environment, gitClient);

    if (userInfo.isComplete()) {
      return userInfo;
    }

    // complete with CI vars if user didn't provide all information
    PullRequestInfo ciInfo =
        PullRequestInfo.coalesce(userInfo, ciProviderInfo.buildPullRequestInfo());
    String headSha = ciInfo.getHeadCommit().getSha();
    if (Strings.isNotBlank(headSha)) {
      // if head sha present try to populate author, committer and message info through git client
      try {
        CommitInfo commitInfo = gitClient.getCommitInfo(headSha, true);
        return PullRequestInfo.coalesce(
            ciInfo, new PullRequestInfo(null, null, null, commitInfo, null));
      } catch (Exception ignored) {
      }
    }
    return ciInfo;
  }

  @Nonnull
  private static PullRequestInfo buildUserPullRequestInfo(
      Config config, CiEnvironment environment, GitClient gitClient) {
    PullRequestInfo userInfo =
        new PullRequestInfo(
            config.getGitPullRequestBaseBranch(),
            config.getGitPullRequestBaseBranchSha(),
            null,
            new CommitInfo(config.getGitCommitHeadSha()),
            null);

    if (userInfo.isComplete()) {
      return userInfo;
    }

    // ddci specific vars
    String targetSha = environment.get(Constants.DDCI_PULL_REQUEST_TARGET_SHA);
    String sourceSha = environment.get(Constants.DDCI_PULL_REQUEST_SOURCE_SHA);
    String mergeBase = null;

    if (!Constants.DDCI_LEGACY_KIND.equals(environment.get(Constants.DDCI_REQUEST_KIND))) {
      // legacy mode doesn't set a valid target sha to compute the merge base
      try {
        mergeBase = gitClient.getMergeBase(targetSha, sourceSha);
      } catch (Exception ignored) {
      }
    }

    PullRequestInfo ddCiInfo =
        new PullRequestInfo(
            null,
            mergeBase,
            null,
            new CommitInfo(environment.get(Constants.DDCI_PULL_REQUEST_SOURCE_SHA)),
            null);

    return PullRequestInfo.coalesce(userInfo, ddCiInfo);
  }

  private static String getRepoRoot(CIInfo ciInfo, GitClient.Factory gitClientFactory) {
    String ciWorkspace = ciInfo.getCiWorkspace();
    if (Strings.isNotBlank(ciWorkspace)) {
      return ciWorkspace;

    } else {
      try {
        return gitClientFactory.create(".").getRepoRoot();

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.error("Interrupted while getting repo root", e);
        return null;

      } catch (Exception e) {
        LOGGER.error("Error while getting repo root", e);
        return null;
      }
    }
  }

  static String getModuleName(Config config, @Nullable String repoRoot, Path path) {
    // if parent process is instrumented, it will provide build system's module name
    String parentModuleName = config.getCiVisibilityModuleName();
    if (parentModuleName != null) {
      return parentModuleName;
    }
    if (repoRoot != null && path.startsWith(repoRoot)) {
      String relativePath = Paths.get(repoRoot).relativize(path).toString();
      if (!relativePath.isEmpty()) {
        return relativePath;
      }
    }
    return config.getServiceName();
  }

  private static ExecutionSettingsFactory buildExecutionSettingsFetcher(
      SignalClient.Factory signalClientFactory) {
    return (JvmInfo jvmInfo, String moduleName) -> {
      try (SignalClient signalClient = signalClientFactory.create()) {
        ExecutionSettingsRequest request =
            new ExecutionSettingsRequest(moduleName, JvmInfo.CURRENT_JVM);
        ExecutionSettingsResponse response = (ExecutionSettingsResponse) signalClient.send(request);
        return response.getSettings();

      } catch (Exception e) {
        LOGGER.error("Could not get module execution settings from parent process", e);
        return ExecutionSettings.EMPTY;
      }
    };
  }

  private static ExecutionSettingsFactory buildExecutionSettingsFactory(
      ProcessHierarchy processHierarchy,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      BackendApi backendApi,
      GitClient gitClient,
      GitRepoUnshallow gitRepoUnshallow,
      GitDataUploader gitDataUploader,
      PullRequestInfo pullRequestInfo,
      @Nullable String repoRoot) {
    ConfigurationApi configurationApi;
    if (backendApi == null) {
      LOGGER.warn(
          "Remote config and skippable tests requests will be skipped since backend API client could not be created");
      configurationApi = ConfigurationApi.NO_OP;
    } else {
      configurationApi = new ConfigurationApiImpl(backendApi, metricCollector);
    }

    ExecutionSettingsFactoryImpl factory =
        new ExecutionSettingsFactoryImpl(
            config,
            configurationApi,
            gitClient,
            gitRepoUnshallow,
            gitDataUploader,
            pullRequestInfo,
            repoRoot);
    if (processHierarchy.isHeadless()) {
      return factory;
    } else {
      return new MultiModuleExecutionSettingsFactory(config, factory);
    }
  }

  private static GitDataUploader buildGitDataUploader(
      Config config,
      CiVisibilityMetricCollector metricCollector,
      GitInfoProvider gitInfoProvider,
      GitClient gitClient,
      GitRepoUnshallow gitRepoUnshallow,
      BackendApi backendApi,
      @Nullable String repoRoot) {
    if (!config.isCiVisibilityGitUploadEnabled()) {
      return () -> CompletableFuture.completedFuture(null);
    }

    if (backendApi == null) {
      LOGGER.warn(
          "Git tree data upload will be skipped since backend API client could not be created");
      return () -> CompletableFuture.completedFuture(null);
    }

    if (repoRoot == null) {
      LOGGER.warn(
          "Git tree data upload will be skipped since Git repository path could not be determined");
      return () -> CompletableFuture.completedFuture(null);
    }

    String remoteName = config.getCiVisibilityGitRemoteName();
    GitDataApi gitDataApi = new GitDataApi(backendApi, metricCollector);
    return new GitDataUploaderImpl(
        config,
        metricCollector,
        gitDataApi,
        gitClient,
        gitRepoUnshallow,
        gitInfoProvider,
        repoRoot,
        remoteName);
  }

  private static SourcePathResolver buildSourcePathResolver(
      @Nullable String repoRoot, RepoIndexProvider indexProvider) {
    SourcePathResolver compilerAidedResolver =
        repoRoot != null
            ? new CompilerAidedSourcePathResolver(repoRoot)
            : NoOpSourcePathResolver.INSTANCE;
    RepoIndexSourcePathResolver indexResolver = new RepoIndexSourcePathResolver(indexProvider);
    return new BestEffortSourcePathResolver(compilerAidedResolver, indexResolver);
  }

  private static Codeowners buildCodeowners(@Nullable String repoRoot) {
    if (repoRoot != null) {
      return new CodeownersProvider().build(repoRoot);
    } else {
      return NoCodeowners.INSTANCE;
    }
  }
}
