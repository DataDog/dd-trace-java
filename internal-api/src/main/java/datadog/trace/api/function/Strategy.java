package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a <b>static-polymorphism strategy</b>: a stateless, concrete-typed policy object that lets
 * one shared algorithm specialize to straight-line code per caller, without runtime virtual
 * dispatch.
 *
 * <p><b>What "static polymorphism" means here.</b> Ordinary (dynamic) polymorphism resolves the
 * implementation at run time — an {@code invokevirtual}/{@code invokeinterface} that can go
 * megamorphic on a shared call site. Static polymorphism instead makes the implementation known to
 * the JIT: hold the strategy in a {@code static final} field of its <i>concrete</i> type (a stable
 * constant of exact type), keep its methods small, and let the consuming method inline. The call
 * site then sees the exact type, so the JIT devirtualizes the strategy's calls and inlines them,
 * and the one generic algorithm compiles to specialized, monomorphic, allocation-free code per
 * caller — C++-template-like specialization, driven by the JIT rather than a code generator. The
 * win is <b>structural</b> (it follows from the exact-typed constant), not a speculative bet on
 * class-hierarchy analysis or type profiling that a second implementation or a polluted profile
 * could quietly undo.
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
