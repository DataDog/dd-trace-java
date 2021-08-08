package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** Tracks message scopes and spans when consuming messages with {@code receive}. */
public final class MessageConsumerState {
  private final ThreadLocal<AgentScope> currentScope = new ThreadLocal<>();
  private final ThreadLocal<TimeInQueue> timeInQueue = new ThreadLocal<>();

  private final SessionState sessionState;
  private final UTF8BytesString resourceName;
  private final boolean propagationDisabled;

  public MessageConsumerState(
      SessionState sessionState, UTF8BytesString resourceName, boolean propagationDisabled) {

    this.sessionState = sessionState;
    this.resourceName = resourceName;
    this.propagationDisabled = propagationDisabled;
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
      if (sessionState.isAutoAcknowledge()) {
        scope.span().finish();
      }
    }
  }

  public AgentSpan getTimeInQueueSpan(long batchId) {
    TimeInQueue holder = timeInQueue.get();
    if (null != holder) {
      // maintain time-in-queue for messages in same batch or same client-ack/transacted session
      if ((batchId > 0 && batchId == holder.batchId) || !sessionState.isAutoAcknowledge()) {
        return holder.span;
      }
      timeInQueue.remove();
      holder.span.finish();
    }
    return null;
  }

  public void setTimeInQueueSpan(long batchId, AgentSpan span) {
    timeInQueue.set(new TimeInQueue(batchId, span));

    // finish new time-in-queue span on message-ack/commit
    if (sessionState.isClientAcknowledge()) {
      sessionState.finishOnAcknowledge(span);
    } else if (sessionState.isTransactedSession()) {
      sessionState.finishOnCommit(span);
    }
  }

  public void finishCurrentTimeInQueueSpan(boolean closing) {
    TimeInQueue holder = timeInQueue.get();
    if (null != holder) {
      // leave in place unless closing, so messages in same batch can link to it
      if (closing) {
        timeInQueue.remove();
      }
      holder.span.finish();
    }
  }
}
