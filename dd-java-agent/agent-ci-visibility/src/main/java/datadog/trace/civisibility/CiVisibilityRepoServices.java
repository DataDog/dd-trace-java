package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CITagsProvider;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.communication.BackendApi;
import datadog.trace.civisibility.config.CachingModuleExecutionSettingsFactory;
import datadog.trace.civisibility.config.ConfigurationApi;
import datadog.trace.civisibility.config.ConfigurationApiImpl;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactoryImpl;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataApi;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.git.tree.GitDataUploaderImpl;
import datadog.trace.civisibility.ipc.ModuleSettingsRequest;
import datadog.trace.civisibility.ipc.ModuleSettingsResponse;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.BestEffortSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.NoOpSourcePathResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.source.index.RepoIndexSourcePathResolver;
import datadog.trace.civisibility.utils.ProcessHierarchyUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Services that need repository root location to be instantiated */
public class CiVisibilityRepoServices {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilityRepoServices.class);

  final String repoRoot;
  final String moduleName;
  final Map<String, String> ciTags;

  final GitDataUploader gitDataUploader;
  final RepoIndexProvider repoIndexProvider;
  final Codeowners codeowners;
  final SourcePathResolver sourcePathResolver;
  final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;

  CiVisibilityRepoServices(CiVisibilityServices services, Path path) {
    CIProviderInfo ciProviderInfo = services.ciProviderInfoFactory.createCIProviderInfo(path);
    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    repoRoot = ciInfo.getCiWorkspace();
    moduleName = getModuleName(services.config, path, ciInfo);
    ciTags = new CITagsProvider().getCiTags(ciInfo);

    gitDataUploader =
        buildGitDataUploader(
            services.config,
            services.gitInfoProvider,
            services.gitClientFactory,
            services.backendApi,
            repoRoot);
    repoIndexProvider = services.repoIndexProviderFactory.create(repoRoot, repoRoot);
    codeowners = buildCodeowners(repoRoot);
    sourcePathResolver = buildSourcePathResolver(repoRoot, repoIndexProvider);

    if (ProcessHierarchyUtils.isChild()) {
      moduleExecutionSettingsFactory =
          buildModuleExecutionSettingsFetcher(services.signalClientFactory);
    } else {
      moduleExecutionSettingsFactory =
          buildModuleExecutionSettingsFactory(
              services.config, services.backendApi, gitDataUploader, repoIndexProvider, repoRoot);
    }
  }

  private static String getModuleName(Config config, Path path, CIInfo ciInfo) {
    // if parent process is instrumented, it will provide build system's module name
    String parentModuleName = config.getCiVisibilityModuleName();
    if (parentModuleName != null) {
      return parentModuleName;
    }
    String repoRoot = ciInfo.getCiWorkspace();
    if (repoRoot != null
        && path.startsWith(repoRoot)
        // module name cannot be empty
        && !path.toString().equals(repoRoot)) {
      return Paths.get(repoRoot).relativize(path).toString();
    }
    return config.getServiceName();
  }

  private static ModuleExecutionSettingsFactory buildModuleExecutionSettingsFetcher(
      SignalClient.Factory signalClientFactory) {
    return (JvmInfo jvmInfo, String moduleName) -> {
      try (SignalClient signalClient = signalClientFactory.create()) {
        ModuleSettingsRequest request = new ModuleSettingsRequest(moduleName, JvmInfo.CURRENT_JVM);
        ModuleSettingsResponse response = (ModuleSettingsResponse) signalClient.send(request);
        return response.getSettings();

      } catch (Exception e) {
        LOGGER.error("Could not get module execution settings from parent process", e);
        return ModuleExecutionSettings.EMPTY;
      }
    };
  }

  private static ModuleExecutionSettingsFactory buildModuleExecutionSettingsFactory(
      Config config,
      BackendApi backendApi,
      GitDataUploader gitDataUploader,
      RepoIndexProvider repoIndexProvider,
      String repoRoot) {
    ConfigurationApi configurationApi;
    if (backendApi == null) {
      LOGGER.warn(
          "Remote config and skippable tests requests will be skipped since backend API client could not be created");
      configurationApi = ConfigurationApi.NO_OP;
    } else {
      configurationApi = new ConfigurationApiImpl(backendApi);
    }
    return new CachingModuleExecutionSettingsFactory(
        config,
        new ModuleExecutionSettingsFactoryImpl(
            config, configurationApi, gitDataUploader, repoIndexProvider, repoRoot));
  }

  private static GitDataUploader buildGitDataUploader(
      Config config,
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
    GitDataApi gitDataApi = new GitDataApi(backendApi);
    GitClient gitClient = gitClientFactory.create(repoRoot);
    return new GitDataUploaderImpl(
        config, gitDataApi, gitClient, gitInfoProvider, repoRoot, remoteName);
  }

  private static SourcePathResolver buildSourcePathResolver(
      String repoRoot, RepoIndexProvider indexProvider) {
    if (repoRoot != null) {
      RepoIndexSourcePathResolver indexSourcePathResolver =
          new RepoIndexSourcePathResolver(repoRoot, indexProvider);
      return new BestEffortSourcePathResolver(
          new CompilerAidedSourcePathResolver(repoRoot), indexSourcePathResolver);
    } else {
      return NoOpSourcePathResolver.INSTANCE;
    }
  }

  private static Codeowners buildCodeowners(String repoRoot) {
    if (repoRoot != null) {
      return new CodeownersProvider().build(repoRoot);
    } else {
      return path -> null;
    }
  }
}
