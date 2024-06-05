package datadog.opentelemetry.tooling;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Replaces OpenTelemetry's {@code TypeInstrumentation} callback when mapping extensions. */
public interface OtelInstrumenter
    extends Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice,
        Instrumenter.HasTypeAdvice {

  @Override
  default String hierarchyMarkerType() {
    return null; // no hint available
  }

  @Override
  default ElementMatcher<TypeDescription> hierarchyMatcher() {
    return typeMatcher();
  }

  @Override
  default void methodAdvice(MethodTransformer methodTransformer) {
    OtelTransformerState.capture(this).with(methodTransformer);
  }

  @Override
  default void typeAdvice(TypeTransformer typeTransformer) {
    OtelTransformerState.capture(this).with(typeTransformer);
  }

  ElementMatcher<TypeDescription> typeMatcher();

  /**
   * Once both transformers have been captured in {@link #methodAdvice} and {@link #typeAdvice} the
   * {@code #transform} method will be called. This allows the extension to register method and type
   * advice at the same time, using the single interface expected by OpenTelemetry.
   */
  void transform(OtelTransformer transformer);
}
