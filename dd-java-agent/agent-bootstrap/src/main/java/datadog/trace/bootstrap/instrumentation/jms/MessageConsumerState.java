package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks message scopes and spans when consuming messages with {@code receive}. */
public final class MessageConsumerState {
  private final Map<Thread, AgentScope> currentScopes = new ConcurrentHashMap<>();

  private final SessionState sessionState;
  private final UTF8BytesString resourceName;
  private final boolean propagationDisabled;

  public MessageConsumerState(
      SessionState sessionState, UTF8BytesString resourceName, boolean propagationDisabled) {
    this.sessionState = sessionState;
    this.resourceName = resourceName;
    this.propagationDisabled = propagationDisabled;

    sessionState.registerConsumerState(this);
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  public UTF8BytesString getResourceName() {
    return resourceName;
  }

  public boolean isPropagationDisabled() {
    return propagationDisabled;
  }

  /** Closes the given message scope when the next message is consumed or the consumer is closed. */
  public void closeOnIteration(AgentScope newScope) {
    maybeCloseScope(currentScopes.put(Thread.currentThread(), newScope));
  }

  public void closePreviousMessageScope() {
    maybeCloseScope(currentScopes.remove(Thread.currentThread()));
  }

  public void onClose() {
    for (AgentScope scope : currentScopes.values()) {
      maybeCloseScope(scope);
    }
    currentScopes.clear();
    sessionState.unregisterConsumerState(this);
  }

  private void maybeCloseScope(AgentScope scope) {
    if (null != scope) {
      scope.close();
      if (sessionState.isAutoAcknowledge()) {
        scope.span().finish();
      }
    }
  }
}
