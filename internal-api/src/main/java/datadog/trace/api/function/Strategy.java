package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a <b>static-polymorphism strategy</b> — a stateless, concrete-typed policy object the JIT
 * can devirtualize and inline, so one shared algorithm specializes to straight-line code per caller
 * (see the "static polymorphism" note on {@code FlatHashtable}).
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * pattern to readers and to give a future checker something to verify. The discipline it names is
 * <b>not yet enforced</b> — hold to it by hand until the checker lands.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): this type is a strategy. To get the
 * specialization a caller must hold it in a {@code static final} field <i>declared with the
 * concrete type</i> (not an abstract base or interface), and the consuming method must inline so
 * the call site sees the exact type. Keep the methods small so they inline.
 *
 * <p><b>On a parameter</b> ({@link ElementType#PARAMETER}): this parameter is a strategy slot. The
 * argument at each call site should be a {@code static final} constant or a <i>non-capturing</i>
 * lambda, so it stays a single monomorphic, allocation-free instance. A parameter can carry this
 * marker even when its type cannot — e.g. a {@code java.util.function.Function} slot we don't own.
 *
 * <p><b>The failure mode is silent.</b> Held at an abstract/interface type, filled with a capturing
 * lambda, or called from a site that doesn't inline, it still compiles and runs correctly — it just
 * stays megamorphic and/or allocates, quietly losing the win. Verify the hot ones with {@code
 * -XX:+PrintInlining}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.PARAMETER})
public @interface Strategy {}
