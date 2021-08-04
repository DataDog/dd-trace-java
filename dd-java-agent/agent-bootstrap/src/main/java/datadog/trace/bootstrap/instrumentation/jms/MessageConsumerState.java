package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public final class MessageConsumerState {
  private final ThreadLocal<AgentScope> currentScope = new ThreadLocal<>();

  private final SessionState sessionState;
  private final int ackMode;
  private final UTF8BytesString resourceName;
  private final String destinationName;

  public MessageConsumerState(
      SessionState sessionState,
      int ackMode,
      UTF8BytesString resourceName,
      String destinationName) {

    this.sessionState = sessionState;
    this.ackMode = ackMode;
    this.resourceName = resourceName;
    this.destinationName = destinationName;
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  public boolean isTransactedSession() {
    return ackMode == 0; /* Session.SESSION_TRANSACTED */
  }

  public boolean isAutoAcknowledge() {
    return ackMode == 1 || ackMode == 3; /*Session.AUTO_ACKNOWLEDGE Session.DUPS_OK_ACKNOWLEDGE */
  }

  public boolean isClientAcknowledge() {
    return ackMode == 2; /* Session.CLIENT_ACKNOWLEDGE */
  }

  public UTF8BytesString getResourceName() {
    return resourceName;
  }

  public String getDestinationName() {
    return destinationName;
  }

  /** Closes the given message scope when the next message is consumed or the consumer is closed. */
  public void closeOnIteration(AgentScope scope) {
    currentScope.set(scope);
  }

  public void closePreviousMessageScope() {
    AgentScope scope = currentScope.get();
    if (null != scope) {
      // remove rather than wait for overwrite because it might
      // be quite a long time before another message arrives
      currentScope.remove();
      scope.close();
      if (isAutoAcknowledge()) {
        scope.span().finish();
      }
    }
  }
}
