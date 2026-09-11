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
 *
 * <p>Retention is {@link RetentionPolicy#CLASS}, matching {@link Strategy}: a checker working from
 * compiled classfiles of a dependent module still needs to see which methods are consumers.
 *
 * <p><b>Checker contract.</b> This annotation names the site, not a separate rule: the shapes to
 * check are {@link Strategy}'s own Checker contract, applied to this method's parameters and call
 * sites. There is one thing specific to this marker worth stating explicitly:
 *
 * <ul>
 *   <li><b>Out of scope (v1):</b> whether a method marked {@code @StrategyConsumer} is actually
 *       small enough to inline. That is a JIT runtime decision (method size, call-site heat,
 *       compilation tier), not a static property a checker can verify from source or classfiles.
 *       Treat this marker as documentation of intent — "this method must stay inlinable" — and
 *       confirm the hot ones actually do with {@code -XX:+PrintInlining}, the same as {@link
 *       Strategy} itself.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface StrategyConsumer {}
