package datadog.trace.bootstrap.instrumentation.jms;

public final class MessageProducerState {
  private final SessionState sessionState;

  public MessageProducerState(SessionState sessionState) {
    this.sessionState = sessionState;
  }

  public SessionState getSessionState() {
    return sessionState;
  }
}
