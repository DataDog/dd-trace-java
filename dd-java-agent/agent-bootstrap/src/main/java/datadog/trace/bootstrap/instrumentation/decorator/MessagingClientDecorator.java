package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public abstract class MessagingClientDecorator extends ClientDecorator {

  protected final boolean endToEndDurationsEnabled;

  protected MessagingClientDecorator() {
    final Config config = Config.get();
    final String[] instrumentationNames = instrumentationNames();
    this.endToEndDurationsEnabled =
        instrumentationNames.length > 0
            && config.isEndToEndDurationEnabled(endToEndDurationsDefault(), instrumentationNames);
  }

  protected boolean endToEndDurationsDefault() {
    return false;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    if (endToEndDurationsEnabled) {
      span.beginEndToEnd();
    }
    return super.afterStart(span);
  }
}
