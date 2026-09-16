package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a higher-order method that <b>consumes</b> {@link Strategy} objects — one whose strategy
 * parameters only specialize if this method itself inlines, so each call site sees the exact
 * strategy type (see {@link Strategy}). Keep it small so it inlines.
 *
 * <p>Documentation-and-tooling marker; it changes no behavior. It pairs with {@link Strategy}: a
 * strategy type/parameter says "I am a strategy / a strategy slot," while this says "I am the site
 * where they must specialize." A future checker can enforce that the arguments filling those slots
 * at these call sites are {@code static final} constants or non-capturing lambdas.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface StrategyConsumer {}
