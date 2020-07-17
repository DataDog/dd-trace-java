package datadog.trace.core.scopemanager;

import com.timgroup.statsd.StatsDClient;
import dagger.Module;
import dagger.Provides;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.core.jfr.DDScopeEventFactory;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class ScopeManagerModule {
  private final AgentScopeManager scopeManager;

  public ScopeManagerModule() {
    this(null);
  }

  public ScopeManagerModule(final AgentScopeManager scopeManager) {
    this.scopeManager = scopeManager;
  }

  @Singleton
  @Provides
  AgentScopeManager scopeManager(
      @Named("scopeDepthLimit") final int scopeDepthLimit,
      final DDScopeEventFactory scopeEventFactory,
      final StatsDClient statsDClient,
      @Named("scopeStrictMode") final boolean isScopeStrictMode) {
    if (scopeManager != null) {
      return scopeManager;
    }
    return new ContinuableScopeManager(
        scopeDepthLimit, scopeEventFactory, statsDClient, isScopeStrictMode);
  }
}
