package datadog.trace.core.scopemanager.context;

import static datadog.trace.core.scopemanager.context.ContextBasedScopeManager.LOGGER;

import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

class ScopeEventManager {
  private final List<ScopeListener> scopeListeners;
  private final List<ExtendedScopeListener> extendedScopeListeners;
  private final Supplier<AgentSpan> activeSpanSupplier;

  ScopeEventManager(Supplier<AgentSpan> activeSpanSupplier) {
    this.activeSpanSupplier = activeSpanSupplier;
    this.scopeListeners = new CopyOnWriteArrayList<>();
    this.extendedScopeListeners = new CopyOnWriteArrayList<>();
  }

  void onScopeActivated(AgentSpan span) {
    for (final ScopeListener listener : this.scopeListeners) {
      try {
        listener.afterScopeActivated();
      } catch (Throwable e) {
        LOGGER.debug("ScopeListener threw exception in afterActivated()", e);
      }
    }

    for (final ExtendedScopeListener listener : this.extendedScopeListeners) {
      try {
        listener.afterScopeActivated(span.getTraceId(), span.getSpanId());
      } catch (Throwable e) {
        LOGGER.debug("ExtendedScopeListener threw exception in afterActivated()", e);
      }
    }
  }

  void onScopeClosed() {
    for (final ScopeListener listener : this.scopeListeners) {
      try {
        listener.afterScopeClosed();
      } catch (Exception e) {
        LOGGER.debug("ScopeListener threw exception in close()", e);
      }
    }

    for (final ExtendedScopeListener listener : this.extendedScopeListeners) {
      try {
        listener.afterScopeClosed();
      } catch (Exception e) {
        LOGGER.debug("ScopeListener threw exception in close()", e);
      }
    }

    AgentSpan activeSpan = this.activeSpanSupplier.get();
    if (activeSpan != null) {
      onScopeActivated(activeSpan);
    }
  }

  void addScopeListener(ScopeListener listener) {
    if (listener == null) {
      return;
    }
    if (listener instanceof ExtendedScopeListener) {
      addExtendedScopeListener((ExtendedScopeListener) listener);
    } else {
      this.scopeListeners.add(listener);
      LOGGER.debug("Added scope listener {}", listener);
      if (this.activeSpanSupplier.get() != null) {
        // Notify the listener about the currently active scope
        listener.afterScopeActivated();
      }
    }
  }

  private void addExtendedScopeListener(ExtendedScopeListener listener) {
    this.extendedScopeListeners.add(listener);
    LOGGER.debug("Added extended scope listener {}", listener);
    AgentSpan activeSpan = this.activeSpanSupplier.get();
    if (activeSpan != null) {
      // Notify the listener about the currently active scope
      listener.afterScopeActivated(activeSpan.getTraceId(), activeSpan.getSpanId());
    }
  }
}
