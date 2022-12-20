package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Map;

public class TagContextExtractor implements HttpCodec.Extractor {

  protected final Map<String, String> taggedHeaders;
  protected final Map<String, String> baggageMapping;
  private final ThreadLocal<ContextInterpreter> ctxInterpreter;

  public TagContextExtractor(
      final Map<String, String> taggedHeaders,
      final Map<String, String> baggageMapping,
      final ContextInterpreter.Factory factory) {
    this.taggedHeaders = taggedHeaders;
    this.baggageMapping = baggageMapping;
    this.ctxInterpreter =
        new ThreadLocal<ContextInterpreter>() {
          @Override
          protected ContextInterpreter initialValue() {
            return factory.create(taggedHeaders, baggageMapping);
          }
        };
  }

  @Override
  public <C> TagContext extract(final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
    ContextInterpreter interpreter = this.ctxInterpreter.get().reset();
    getter.forEachKey(carrier, interpreter);
    return interpreter.build();
  }
}
