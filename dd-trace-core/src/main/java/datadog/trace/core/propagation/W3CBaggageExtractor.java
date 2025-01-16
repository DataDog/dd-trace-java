package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class W3CBaggageExtractor implements HttpCodec.Extractor {
  private static final Logger LOGGER = LoggerFactory.getLogger(W3CBaggageExtractor.class);
  private final HttpCodec.Extractor delegate;

  public W3CBaggageExtractor(HttpCodec.Extractor delegate) {
    this.delegate = delegate;
  }

  @Override
  public <C> TagContext extract(C carrier, AgentPropagation.ContextVisitor<C> getter) {
    // delegate to the default HTTP extraction
    TagContext context = this.delegate.extract(carrier, getter);

    // TODO: extract baggage from carrier and add it to context

    return context;
  }
}
