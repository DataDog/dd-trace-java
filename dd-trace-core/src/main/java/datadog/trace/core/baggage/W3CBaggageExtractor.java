package datadog.trace.core.baggage;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.propagation.HttpCodec;
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
    return this.delegate.extract(carrier, getter);
  }
}
