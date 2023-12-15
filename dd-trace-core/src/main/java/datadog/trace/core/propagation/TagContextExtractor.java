package datadog.trace.core.propagation;

import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.function.Supplier;

public class TagContextExtractor implements HttpCodec.Extractor {

  private final Supplier<TraceConfig> traceConfigSupplier;
  private final ThreadLocal<ContextInterpreter> ctxInterpreter;

  public TagContextExtractor(
      final Supplier<TraceConfig> traceConfigSupplier, final ContextInterpreter.Factory factory) {
    this.traceConfigSupplier = traceConfigSupplier;
    this.ctxInterpreter = ThreadLocal.withInitial(factory::create);
  }

  @Override
  public <C> TagContext extract(final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
    ContextInterpreter interpreter = this.ctxInterpreter.get().reset(traceConfigSupplier.get());
    getter.forEachKey(carrier, interpreter);
    return interpreter.build();
  }

  @Override
  public void cleanup() {
    ctxInterpreter.remove();
  }
}
