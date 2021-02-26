package datadog.trace.security;

import datadog.trace.bootstrap.security.Engine;
import datadog.trace.bootstrap.security.PassthruAdviceException;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

@Slf4j
public class EngineImpl extends Engine {
  private final ContinuableScopeManager scopeManager;

  SortedSet<DataSubscription> subscriptions = new TreeSet<>();

  public EngineImpl(ContinuableScopeManager scopeManager) {
    this.scopeManager = scopeManager;
  }

  private DataSource createActiveSpansDataSource() {
    ContinuableScopeManager.ScopeStack scopeStack = this.scopeManager.scopeStack();
    return new DataSource.ScopeStackDataSource(scopeStack);
  }

  public void addSubscription(DataSubscription sub) {
    subscriptions.add(sub);
  }

  @Override
  public void deliverNotifications(Set<String> newAddressKeys) {
    AgentSpan activeSpan = scopeManager.activeSpan();
    if (activeSpan == null) {
      log.warn("Cannot deliver notifications without an active span");
      return;
    }

    DataSource topSpanData = new DataSource.SpanDataSource(activeSpan);
    DataSource allData = createActiveSpansDataSource();

    // TODO: needs to be optimized
    Flow f = new FlowImpl();
    for (DataSubscription subscription : subscriptions) {
      if (subscription.matches(newAddressKeys, allData)) {
        subscription.getListener().dataAvailable(f, topSpanData, allData);
      }
    }

    if (f.hasException()) {
      throw new PassthruAdviceException(f.getException());
    }
  }

}
