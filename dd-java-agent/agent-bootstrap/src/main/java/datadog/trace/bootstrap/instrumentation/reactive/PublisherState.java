package datadog.trace.bootstrap.instrumentation.reactive;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes track of the span that was active at subscribe time and also to all the spans created as
 * async result that needs to be finished when the publisher finishes
 */
public class PublisherState {
  private AgentSpan subscriptionSpan;
  private final List<AgentSpan> partnerSpans = new ArrayList<>();

  public PublisherState withSubscriptionSpan(final AgentSpan span) {
    this.subscriptionSpan = span;
    return this;
  }

  public PublisherState withPartnerSpan(final AgentSpan span) {
    partnerSpans.add(span);
    return this;
  }

  public AgentSpan getSubscriptionSpan() {
    return subscriptionSpan;
  }

  public List<AgentSpan> getPartnerSpans() {
    return partnerSpans;
  }
}
