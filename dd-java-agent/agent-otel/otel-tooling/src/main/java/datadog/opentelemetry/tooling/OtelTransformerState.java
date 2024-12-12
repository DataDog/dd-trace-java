package datadog.opentelemetry.tooling;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * {@link OtelTransformer} state captured when processing OpenTelemetry extensions with {@code
 * CombiningTransformerBuilder}. Assumes that the builder is single threaded.
 *
 * <p>OpenTelemetry has a single transformer callback with methods to register method advice and
 * type transformations at the same time, whereas the Datadog tracer has separate {@link
 * Instrumenter.MethodTransformer} and {@link Instrumenter.TypeTransformer} callbacks.
 *
 * <p>To map between the two we capture the Datadog method and type transformers here, from calls to
 * {@link OtelInstrumenter}. Once we have captured both transformers we trigger the single transform
 * request through the mapped OpenTelemetry callback.
 */
final class OtelTransformerState implements OtelTransformer {
  private static final OtelTransformerState CURRENT = new OtelTransformerState();

  private OtelInstrumenter instrumenter;
  private Instrumenter.MethodTransformer methodTransformer;
  private Instrumenter.TypeTransformer typeTransformer;

  static OtelTransformerState capture(OtelInstrumenter instrumenter) {
    if (instrumenter != CURRENT.instrumenter) {
      CURRENT.reset();
      CURRENT.instrumenter = instrumenter;
    }
    return CURRENT;
  }

  void with(Instrumenter.MethodTransformer methodTransformer) {
    this.methodTransformer = methodTransformer;
    if (null != this.typeTransformer) {
      triggerTransform();
    }
  }

  void with(Instrumenter.TypeTransformer typeTransformer) {
    this.typeTransformer = typeTransformer;
    if (null != this.methodTransformer) {
      triggerTransform();
    }
  }

  private void triggerTransform() {
    try {
      instrumenter.transform(this);
    } finally {
      reset();
    }
  }

  private void reset() {
    this.instrumenter = null;
    this.methodTransformer = null;
    this.typeTransformer = null;
  }

  @Override
  public void applyAdviceToMethod(
      ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
    methodTransformer.applyAdvice(methodMatcher, adviceClassName);
  }

  @Override
  public void applyTransformer(AgentBuilder.Transformer transformer) {
    typeTransformer.applyAdvice(transformer::transform);
  }
}
