package datadog.trace.bootstrap.instrumentation.api;

import static datadog.context.Context.root;
import static datadog.context.propagation.Concern.named;
import static datadog.context.propagation.Concern.withPriority;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Concern;
import datadog.context.propagation.Propagators;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.ParametersAreNonnullByDefault;

public final class AgentPropagation {
  public static final Concern TRACING_CONCERN = named("tracing");
  // TODO: Baggage propagator should run after tracing so it can link baggage with the span context
  // TODO: remove this priority once we have a story for replacing TagContext with the Context API
  public static final Concern BAGGAGE_CONCERN = withPriority("baggage", 105);
  public static final Concern XRAY_TRACING_CONCERN = named("tracing-xray");
  public static final Concern INFERRED_PROXY_CONCERN = named("inferred-proxy");
  // TODO DSM propagator should run after the other propagators as it stores the pathway context
  // TODO into the span context for now. Remove priority after the migration is complete.
  public static final Concern DSM_CONCERN = withPriority("data-stream-monitoring", 110);

  private AgentPropagation() {}

  /**
   * @deprecated Use {@link Propagators} API instead.
   */
  @Deprecated
  public static <C> AgentSpanContext.Extracted extractContextAndGetSpanContext(
      final C carrier, final ContextVisitor<C> getter) {
    Context extracted = Propagators.defaultPropagator().extract(root(), carrier, getter);
    AgentSpan extractedSpan = fromContext(extracted);
    return extractedSpan == null ? null : (AgentSpanContext.Extracted) extractedSpan.spanContext();
  }

  public interface KeyClassifier {
    boolean accept(String key, String value);

    /**
     * Variant of {@link #accept(String, String)} for carriers that store header values in a raw
     * form (e.g. {@code byte[]}) and want to defer string conversion until after the key is known
     * to be relevant.
     *
     * <p>The default implementation applies {@code transformer} eagerly and delegates to {@link
     * #accept(String, String)}, so existing classifiers work without any changes.
     *
     * @param key the header name
     * @param value the raw header value, in whatever form the carrier provides
     * @param transformer converts {@code value} to a string; called at most once by the default
     *     implementation
     * @return {@code false} to stop iteration, {@code true} to continue
     */
    default <T> boolean accept(String key, T value, Function<T, String> transformer) {
      return accept(key, transformer.apply(value));
    }
  }

  public interface ContextVisitor<C> extends CarrierVisitor<C> {
    void forEachKey(C carrier, KeyClassifier classifier);

    @ParametersAreNonnullByDefault
    @Override
    default void forEachKeyValue(C carrier, BiConsumer<String, String> visitor) {
      forEachKey(
          carrier,
          (key, value) -> {
            visitor.accept(key, value);
            return true;
          });
    }
  }
}
