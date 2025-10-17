package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Tracks message spans when consuming messages with {@code receive}. */
public final class MessageConsumerState {

  private final SessionState sessionState;
  private final CharSequence brokerResourceName;
  private final String brokerServiceName;
  private final CharSequence consumerBaseResourceName;
  private final CharSequence consumerResourceName;
  private final boolean propagationDisabled;

  public MessageConsumerState(
      SessionState sessionState,
      CharSequence brokerResourceName,
      CharSequence consumerBaseResourceName,
      CharSequence consumerResourceName,
      boolean propagationDisabled) {
    this.sessionState = sessionState;
    this.brokerResourceName = brokerResourceName;
    this.consumerBaseResourceName = consumerBaseResourceName;
    this.consumerResourceName = consumerResourceName;
    this.propagationDisabled = propagationDisabled;

    if (Config.get().isMessageBrokerSplitByDestination()) {
      // use the destination as the service name, with no prefix
      String brokerServiceName = brokerResourceName.toString();
      if (brokerServiceName.startsWith("Queue ") || brokerServiceName.startsWith("Topic ")) {
        this.brokerServiceName = brokerServiceName.substring(6);
      } else {
        this.brokerServiceName = brokerServiceName;
      }
    } else {
      this.brokerServiceName = null;
    }
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  public CharSequence getBrokerResourceName() {
    return brokerResourceName;
  }

  public String getBrokerServiceName() {
    return brokerServiceName;
  }

  public CharSequence getConsumerBaseResourceName() {
    return consumerBaseResourceName;
  }

  public CharSequence getConsumerResourceName() {
    return consumerResourceName;
  }

  public boolean isPropagationDisabled() {
    return propagationDisabled;
  }

  /** Gets the current time-in-queue span; returns {@code null} if this is a new batch. */
  public AgentSpan getTimeInQueueSpan(long batchId) {
    return sessionState.getTimeInQueueSpan(batchId); // tracked per-session-thread
  }

  /** Starts tracking a new time-in-queue span. */
  public void setTimeInQueueSpan(long batchId, AgentSpan span) {
    sessionState.setTimeInQueueSpan(batchId, span); // tracked per-session-thread
  }

  /** Finishes the current time-in-queue span and optionally stops tracking it. */
  public void finishTimeInQueueSpan(boolean clear) {
    sessionState.finishTimeInQueueSpan(clear); // tracked per-session-thread
  }
}
