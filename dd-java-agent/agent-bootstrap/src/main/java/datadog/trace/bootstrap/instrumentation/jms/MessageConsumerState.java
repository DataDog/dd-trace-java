package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public final class MessageConsumerState {

  private final Object session;
  private final int ackMode;
  private final UTF8BytesString resourceName;
  private final ThreadLocal<AgentScope> currentScope = new ThreadLocal<>();

  public MessageConsumerState(Object session, int ackMode, UTF8BytesString resourceName) {
    this.session = session;
    this.ackMode = ackMode;
    this.resourceName = resourceName;
  }

  @SuppressWarnings("unchecked")
  public <T> T getSession() {
    return (T) session;
  }

  public boolean isTransactedSession() {
    return ackMode == 0; /* Session.SESSION_TRANSACTED */
  }

  public boolean isAutoAcknowledge() {
    return ackMode == 1 || ackMode == 3; /*Session.AUTO_ACKNOWLEDGE Session.DUPS_OK_ACKNOWLEDGE */
  }

  public boolean isClientAcknowledge() {
    /* Session.CLIENT_ACKNOWLEDGE */
    return ackMode == 2;
  }

  public UTF8BytesString getResourceName() {
    return resourceName;
  }

  public void capture(AgentScope scope) {
    this.currentScope.set(scope);
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
