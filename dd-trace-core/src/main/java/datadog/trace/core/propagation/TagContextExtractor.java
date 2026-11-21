package datadog.trace.core.propagation;

import datadog.context.propagation.CarrierKeys;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.function.Supplier;

public class TagContextExtractor implements HttpCodec.Extractor {

  private final Supplier<TraceConfig> traceConfigSupplier;
  private final ContextInterpreter.Factory factory;
  private final ThreadLocal<ContextInterpreter> ctxInterpreter;

  /** The interpreter's keys for the last trace config they were asked for. */
  private volatile KeysForConfig keys;

  public TagContextExtractor(
      final Supplier<TraceConfig> traceConfigSupplier, final ContextInterpreter.Factory factory) {
    this.traceConfigSupplier = traceConfigSupplier;
    this.factory = factory;
    this.ctxInterpreter = ThreadLocal.withInitial(factory::create);
  }

  @Override
  public <C> TagContext extract(final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
    return interpret(carrier, getter).build();
  }

  /**
   * The keys this extractor's interpreter reads under the current trace config. Rebuilt only when
   * the trace config changes (for instance, remote config changing the request header tags).
   */
  @Override
  public CarrierKeys keys() {
    TraceConfig traceConfig = traceConfigSupplier.get();
    KeysForConfig current = this.keys;
    if (current != null && current.traceConfig == traceConfig) {
      return current.keys;
    }
    ContextInterpreter interpreter = current != null ? current.interpreter : this.factory.create();
    KeysForConfig rebuilt =
        new KeysForConfig(traceConfig, interpreter, interpreter.keys(traceConfig));
    this.keys = rebuilt;
    return rebuilt.keys;
  }

  /** This thread's interpreter, as the last {@link #interpret} left it. */
  protected final ContextInterpreter interpreter() {
    return this.ctxInterpreter.get();
  }

  /** Runs this thread's interpreter over the carrier, ready to build. */
  protected final <C> ContextInterpreter interpret(
      final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
    ContextInterpreter interpreter = this.ctxInterpreter.get().reset(traceConfigSupplier.get());
    getter.forEachKey(carrier, interpreter);
    return interpreter;
  }

  @Override
  public void cleanup() {
    ctxInterpreter.remove();
  }

  /**
   * Keys built for one trace config, with the interpreter used to build them (kept so a config
   * change doesn't create another; key building reads only its config-derived fields).
   */
  private static final class KeysForConfig {
    final TraceConfig traceConfig;
    final ContextInterpreter interpreter;
    final CarrierKeys keys;

    KeysForConfig(TraceConfig traceConfig, ContextInterpreter interpreter, CarrierKeys keys) {
      this.traceConfig = traceConfig;
      this.interpreter = interpreter;
      this.keys = keys;
    }
  }
}
