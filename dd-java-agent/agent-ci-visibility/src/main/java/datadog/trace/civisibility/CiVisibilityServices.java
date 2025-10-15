package datadog.trace.civisibility;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOUtils;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Command;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.api.intake.Intake;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl;
import datadog.trace.civisibility.ci.env.CompositeCiEnvironment;
import datadog.trace.civisibility.config.CachingJvmInfoFactory;
import datadog.trace.civisibility.config.JvmInfoFactory;
import datadog.trace.civisibility.config.JvmInfoFactoryImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.git.GitClientGitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.NoOpGitClient;
import datadog.trace.civisibility.git.tree.ShellGitClient;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.BestEffortLinesResolver;
import datadog.trace.civisibility.source.ByteCodeLinesResolver;
import datadog.trace.civisibility.source.CompilerAidedLinesResolver;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.index.*;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.File;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Services that do not need repository root location to be instantiated. Can be shared between
 * multiple sessions.
 */
public class CiVisibilityServices {

  private static final Logger logger = LoggerFactory.getLogger(CiVisibilityServices.class);

  private static final String GIT_FOLDER_NAME = ".git";

  static final String DD_ENV_VARS_PROVIDER_KEY_HEADER = "DD-Env-Vars-Provider-Key";

  final ProcessHierarchy processHierarchy;
  final Config config;
  final CiVisibilityMetricCollector metricCollector;
  final BackendApi backendApi;
  final BackendApi ciIntake;
  final JvmInfoFactory jvmInfoFactory;
  final CiEnvironment environment;
  final CIProviderInfoFactory ciProviderInfoFactory;
  final GitClient.Factory gitClientFactory;
  final GitInfoProvider gitInfoProvider;
  final LinesResolver linesResolver;
  final RepoIndexProvider.Factory repoIndexProviderFactory;
  @Nullable final SignalClient.Factory signalClientFactory;

  CiVisibilityServices(
      Config config,
      CiVisibilityMetricCollector metricCollector,
      SharedCommunicationObjects sco,
      GitInfoProvider gitInfoProvider) {
    this.processHierarchy = new ProcessHierarchy();
    this.config = config;
    this.metricCollector = metricCollector;
    this.backendApi = new BackendApiFactory(config, sco).createBackendApi(Intake.API);
    this.ciIntake = new BackendApiFactory(config, sco).createBackendApi(Intake.CI_INTAKE);
    this.jvmInfoFactory = new CachingJvmInfoFactory(config, new JvmInfoFactoryImpl());
    this.gitClientFactory = buildGitClientFactory(config, metricCollector);

    this.environment = buildCiEnvironment(config, sco);
    this.ciProviderInfoFactory = new CIProviderInfoFactory(config, environment);
    this.linesResolver =
        new BestEffortLinesResolver(new CompilerAidedLinesResolver(), new ByteCodeLinesResolver());

    this.gitInfoProvider = gitInfoProvider;
    gitInfoProvider.registerGitInfoBuilder(new CIProviderGitInfoBuilder(config, environment));
    gitInfoProvider.registerGitInfoBuilder(
        new CILocalGitInfoBuilder(gitClientFactory, GIT_FOLDER_NAME));
    gitInfoProvider.registerGitInfoBuilder(new GitClientGitInfoBuilder(config, gitClientFactory));

    if (processHierarchy.isChild()) {
      InetSocketAddress signalServerAddress = processHierarchy.getSignalServerAddress();
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

  private static GitClient.Factory buildGitClientFactory(
      Config config, CiVisibilityMetricCollector metricCollector) {
    if (!config.isCiVisibilityGitClientEnabled()) {
      return r -> NoOpGitClient.INSTANCE;
    }
    try {
      ShellCommandExecutor shellCommandExecutor =
          new ShellCommandExecutor(new File("."), config.getCiVisibilityGitCommandTimeoutMillis());
      String gitVersion = shellCommandExecutor.executeCommand(IOUtils::readFully, "git", "version");
      logger.debug("Detected git executable version {}", gitVersion);
      return new ShellGitClient.Factory(config, metricCollector);

    } catch (Exception e) {
      metricCollector.add(
          CiVisibilityCountMetric.GIT_COMMAND_ERRORS,
          1,
          Command.OTHER,
          ShellCommandExecutor.getExitCode(e));
      logger.info("No git executable detected, some features will not be available");
      return r -> NoOpGitClient.INSTANCE;
    }
  }

  @Nonnull
  private static CiEnvironment buildCiEnvironment(Config config, SharedCommunicationObjects sco) {
    CiEnvironment localEnvironment = CiEnvironmentImpl.local();
    String remoteEnvVarsProviderUrl = config.getCiVisibilityRemoteEnvVarsProviderUrl();
    if (remoteEnvVarsProviderUrl != null) {
      String remoteEnvVarsProviderKey = config.getCiVisibilityRemoteEnvVarsProviderKey();
      CiEnvironment remoteEnvironment =
          new CiEnvironmentImpl(
              getRemoteEnvironment(
                  remoteEnvVarsProviderUrl, remoteEnvVarsProviderKey, sco.agentHttpClient));
      return new CompositeCiEnvironment(remoteEnvironment, localEnvironment);
    } else {
      return localEnvironment;
    }
  }

  static Map<String, String> getRemoteEnvironment(String url, String key, OkHttpClient httpClient) {
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

    HttpUrl httpUrl = HttpUrl.get(url);
    Request request =
        new Request.Builder()
            .url(httpUrl)
            .header(DD_ENV_VARS_PROVIDER_KEY_HEADER, key)
            .get()
            .build();
    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {

      if (response.isSuccessful()) {
        Moshi moshi = new Moshi.Builder().build();
        Type type = Types.newParameterizedType(Map.class, String.class, String.class);
        JsonAdapter<Map<String, String>> adapter = moshi.adapter(type);
        return adapter.fromJson(response.body().source());
      } else {
        logger.warn(
            "Could not get remote CI environment (HTTP code {}) {}",
            response.code(),
            response.body() != null ? ": " + response.body().string() : "");
        return Collections.emptyMap();
      }

    } catch (Exception e) {
      logger.warn("Could not get remote CI environment", e);
      return Collections.emptyMap();
    }
  }

  CiVisibilityRepoServices repoServices(Path path) {
    return new CiVisibilityRepoServices(this, path);
  }
}
