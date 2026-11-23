package datadog.trace.civisibility;

import static org.mockito.Mockito.mock;

import datadog.communication.BackendApi;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.config.ExecutionSettingsFactoryImpl;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import java.lang.reflect.Field;

final class CiVisibilityRepoServicesTestFixture {
  private CiVisibilityRepoServicesTestFixture() {}

  /** Creates the shared repository-service test wiring. */
  static CiVisibilityServices createServices(
      Config config,
      CiEnvironment environment,
      CIProviderInfoFactory providers,
      GitClient.Factory clients,
      GitInfoProvider selected,
      ProcessHierarchy hierarchy,
      RepoIndexProvider.Factory indexes)
      throws ReflectiveOperationException {
    CiVisibilityServices services = mock(CiVisibilityServices.class);
    setServiceField(services, "config", config);
    setServiceField(services, "environment", environment);
    setServiceField(services, "ciProviderInfoFactory", providers);
    setServiceField(services, "gitClientFactory", clients);
    setServiceField(services, "gitInfoProvider", selected);
    setServiceField(services, "processHierarchy", hierarchy);
    setServiceField(services, "repoIndexProviderFactory", indexes);
    setServiceField(services, "backendApi", mock(BackendApi.class));
    setServiceField(services, "metricCollector", mock(CiVisibilityMetricCollector.class));
    return services;
  }

  /** Reads the PR metadata supplied to execution settings. */
  static PullRequestInfo settingsPullRequestInfo(CiVisibilityRepoServices repository)
      throws ReflectiveOperationException {
    Field field = ExecutionSettingsFactoryImpl.class.getDeclaredField("pullRequestInfo");
    field.setAccessible(true);
    return (PullRequestInfo) field.get(repository.executionSettingsFactory);
  }

  private static void setServiceField(CiVisibilityServices services, String name, Object value)
      throws ReflectiveOperationException {
    Field field = CiVisibilityServices.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(services, value);
  }
}
