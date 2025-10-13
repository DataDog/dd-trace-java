package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;

/**
 * TagsPostProcessor that applies HTTP endpoint tagging logic to spans. This processor computes and
 * sets the http.endpoint tag based on http.route and http.url tags when appropriate.
 */
public final class HttpEndpointPostProcessor extends TagsPostProcessor {
  private final Config config;

  public HttpEndpointPostProcessor() {
    this(Config.get());
  }

  // Visible for testing
  HttpEndpointPostProcessor(Config config) {
    this.config = config;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    // Use direct map access for better performance
    HttpEndpointTagging.setEndpointTag(unsafeTags, config);
  }
}
