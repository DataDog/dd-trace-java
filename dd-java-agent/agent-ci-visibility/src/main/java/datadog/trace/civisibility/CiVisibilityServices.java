package datadog.trace.civisibility;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.config.CachingJvmInfoFactory;
import datadog.trace.civisibility.config.JvmInfoFactory;
import datadog.trace.civisibility.config.JvmInfoFactoryImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.git.GitClientGitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.BestEffortMethodLinesResolver;
import datadog.trace.civisibility.source.ByteCodeMethodLinesResolver;
import datadog.trace.civisibility.source.CompilerAidedMethodLinesResolver;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.index.CachingRepoIndexBuilderFactory;
import datadog.trace.civisibility.source.index.ConventionBasedResourceResolver;
import datadog.trace.civisibility.source.index.PackageResolver;
import datadog.trace.civisibility.source.index.PackageResolverImpl;
import datadog.trace.civisibility.source.index.RepoIndexFetcher;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.source.index.ResourceResolver;
import datadog.trace.civisibility.utils.ProcessHierarchyUtils;
import java.net.InetSocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Services that do not need repository root location to be instantiated */
public class CiVisibilityServices {

  private static final String GIT_FOLDER_NAME = ".git";

  final Config config;
  final CiVisibilityMetricCollector metricCollector;
  final BackendApi backendApi;
  final JvmInfoFactory jvmInfoFactory;
  final CIProviderInfoFactory ciProviderInfoFactory;
  final GitClient.Factory gitClientFactory;
  final GitInfoProvider gitInfoProvider;
  final MethodLinesResolver methodLinesResolver;
  final RepoIndexProvider.Factory repoIndexProviderFactory;
  @Nullable final SignalClient.Factory signalClientFactory;

  CiVisibilityServices(
      Config config,
      CiVisibilityMetricCollector metricCollector,
      SharedCommunicationObjects sco,
      GitInfoProvider gitInfoProvider) {
    this.config = config;
    this.metricCollector = metricCollector;
    this.backendApi =
        new BackendApiFactory(config, sco).createBackendApi(BackendApiFactory.Intake.API);
    this.jvmInfoFactory = new CachingJvmInfoFactory(config, new JvmInfoFactoryImpl());
    this.gitClientFactory = new GitClient.Factory(config, metricCollector);
    this.ciProviderInfoFactory = new CIProviderInfoFactory(config);
    this.methodLinesResolver =
        new BestEffortMethodLinesResolver(
            new CompilerAidedMethodLinesResolver(), new ByteCodeMethodLinesResolver());

    this.gitInfoProvider = gitInfoProvider;
    gitInfoProvider.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    gitInfoProvider.registerGitInfoBuilder(
        new CILocalGitInfoBuilder(gitClientFactory, GIT_FOLDER_NAME));
    gitInfoProvider.registerGitInfoBuilder(new GitClientGitInfoBuilder(config, gitClientFactory));

    if (ProcessHierarchyUtils.isChild()) {
      InetSocketAddress signalServerAddress = ProcessHierarchyUtils.getSignalServerAddress();
      this.signalClientFactory = new SignalClient.Factory(signalServerAddress, config);

      RepoIndexProvider indexFetcher = new RepoIndexFetcher(signalClientFactory);
      this.repoIndexProviderFactory = (repoRoot) -> indexFetcher;

    } else {
      this.signalClientFactory = null;

      FileSystem fileSystem = FileSystems.getDefault();
      PackageResolver packageResolver = new PackageResolverImpl(fileSystem);
      ResourceResolver resourceResolver =
          new ConventionBasedResourceResolver(
              fileSystem, config.getCiVisibilityResourceFolderNames());
      this.repoIndexProviderFactory =
          new CachingRepoIndexBuilderFactory(config, packageResolver, resourceResolver, fileSystem);
    }
  }

  CiVisibilityRepoServices repoServices(Path path) {
    return new CiVisibilityRepoServices(this, path);
  }
}
