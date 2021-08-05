package datadog.trace.bootstrap.instrumentation.jms;

public final class MessageProducerState {
  public static final String JMS_PRODUCED_KEY = "x_datadog_jms_produced";

  private final SessionState sessionState;

  public MessageProducerState(SessionState sessionState) {
    this.sessionState = sessionState;
  }

  public SessionState getSessionState() {
    return sessionState;
  }
}
