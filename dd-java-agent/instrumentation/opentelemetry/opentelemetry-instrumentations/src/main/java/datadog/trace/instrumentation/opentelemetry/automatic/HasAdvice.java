package datadog.trace.instrumentation.opentelemetry.automatic;

import datadog.trace.agent.tooling.Instrumenter;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This interface delegates calls from Datadog {@link Instrumenter.HasTypeAdvice} and {@link
 * Instrumenter.HasMethodAdvice} to the OpenTelemetry {@link
 * #transform(io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer)} method. This is
 * an interface with default methods rather than a base class to easily inject common methods
 * without changing parent type.
 */
public interface HasAdvice
    extends Instrumenter.HasMethodAdvice, Instrumenter.HasTypeAdvice, TypeInstrumentation {
  @Override
  default void methodAdvice(MethodTransformer transformer) {
    transform(TypeTransformerWrapper.of(transformer));
  }

  @Override
  default void typeAdvice(TypeTransformer transformer) {
    transform(TypeTransformerWrapper.of(transformer));
  }

  class TypeTransformerWrapper
      implements io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer {
    final MethodTransformer methodTransformer;
    final TypeTransformer typeTransformer;

    private TypeTransformerWrapper(
        MethodTransformer methodTransformer, TypeTransformer typeTransformer) {
      this.methodTransformer = methodTransformer;
      this.typeTransformer = typeTransformer;
    }

    static TypeTransformerWrapper of(MethodTransformer methodTransformer) {
      return new TypeTransformerWrapper(methodTransformer, null);
    }

    static TypeTransformerWrapper of(TypeTransformer typeTransformer) {
      return new TypeTransformerWrapper(null, typeTransformer);
    }

    @Override
    public void applyAdviceToMethod(
        ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
      if (this.methodTransformer != null) {
        this.methodTransformer.applyAdvice(methodMatcher, adviceClassName);
      }
    }

    @Override
    public void applyTransformer(AgentBuilder.Transformer transformer) {
      if (this.typeTransformer != null) {
        this.typeTransformer.applyAdvice(transformer::transform);
      }
    }
  }
}
