package datadog.trace.core.propagation;

import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextKey;
import java.util.Set;
import java.util.function.Supplier;

public class TagContextExtractor implements HttpCodec.Extractor {

  private final Supplier<TraceConfig> traceConfigSupplier;
  private final Set<ContextKey<?>> supportedContent;
  private final ThreadLocal<ContextInterpreter> ctxInterpreter;

  public TagContextExtractor(
      final Supplier<TraceConfig> traceConfigSupplier,
      final Set<ContextKey<?>> supportedContext,
      final ContextInterpreter.Factory factory) {
    this.traceConfigSupplier = traceConfigSupplier;
    this.supportedContent = supportedContext;
    this.ctxInterpreter = ThreadLocal.withInitial(factory::create);
  }

  @Override
  public Set<ContextKey<?>> supportedContent() {
    return this.supportedContent;
  }

  @Override
  public <C> void extract(
      final HttpCodec.ScopeContextBuilder builder,
      final C carrier,
      final AgentPropagation.ContextVisitor<C> getter) {
    ContextInterpreter interpreter = this.ctxInterpreter.get().reset(traceConfigSupplier.get());
    getter.forEachKey(carrier, interpreter);
    interpreter.build(builder);
  }
}
