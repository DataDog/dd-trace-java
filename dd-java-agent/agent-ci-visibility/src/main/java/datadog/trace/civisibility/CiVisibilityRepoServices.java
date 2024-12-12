package datadog.trace.civisibility;

import datadog.communication.BackendApi;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CITagsProvider;
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
import datadog.trace.civisibility.ipc.ExecutionSettingsRequest;
import datadog.trace.civisibility.ipc.ExecutionSettingsResponse;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.BestEffortSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.NoOpSourcePathResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.source.index.RepoIndexSourcePathResolver;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Services that need repository root location to be instantiated. The scope is session. */
public class CiVisibilityRepoServices {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilityRepoServices.class);

  final String repoRoot;
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
    repoRoot = ciInfo.getNormalizedCiWorkspace();
    moduleName = getModuleName(services.config, path, ciInfo);
    ciTags = new CITagsProvider().getCiTags(ciInfo);

    gitDataUploader =
        buildGitDataUploader(
            services.config,
            services.metricCollector,
            services.gitInfoProvider,
            services.gitClientFactory,
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
              gitDataUploader,
              repoRoot);
    }
  }

  static String getModuleName(Config config, Path path, CIInfo ciInfo) {
    // if parent process is instrumented, it will provide build system's module name
    String parentModuleName = config.getCiVisibilityModuleName();
    if (parentModuleName != null) {
      return parentModuleName;
    }
    String repoRoot = ciInfo.getNormalizedCiWorkspace();
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
      GitDataUploader gitDataUploader,
      String repoRoot) {
    ConfigurationApi configurationApi;
    if (backendApi == null) {
      LOGGER.warn(
          "Remote config and skippable tests requests will be skipped since backend API client could not be created");
      configurationApi = ConfigurationApi.NO_OP;
    } else {
      configurationApi = new ConfigurationApiImpl(backendApi, metricCollector);
    }

    ExecutionSettingsFactoryImpl factory =
        new ExecutionSettingsFactoryImpl(config, configurationApi, gitDataUploader, repoRoot);
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
      GitClient.Factory gitClientFactory,
      BackendApi backendApi,
      String repoRoot) {
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
    GitClient gitClient = gitClientFactory.create(repoRoot);
    return new GitDataUploaderImpl(
        config, metricCollector, gitDataApi, gitClient, gitInfoProvider, repoRoot, remoteName);
  }

  private static SourcePathResolver buildSourcePathResolver(
      String repoRoot, RepoIndexProvider indexProvider) {
    SourcePathResolver compilerAidedResolver =
        repoRoot != null
            ? new CompilerAidedSourcePathResolver(repoRoot)
            : NoOpSourcePathResolver.INSTANCE;
    RepoIndexSourcePathResolver indexResolver = new RepoIndexSourcePathResolver(indexProvider);
    return new BestEffortSourcePathResolver(compilerAidedResolver, indexResolver);
  }

  private static Codeowners buildCodeowners(String repoRoot) {
    if (repoRoot != null) {
      return new CodeownersProvider().build(repoRoot);
    } else {
      return NoCodeowners.INSTANCE;
    }
  }
}
