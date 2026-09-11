package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that must be confined to a background thread the tracer owns and paces itself -- e.g.
 * serialization, stats aggregation, or eviction -- and must never be reached from an application
 * thread (the foreground; see {@link ForegroundSafe}), where its cost would become customer-visible
 * latency instead.
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * constraint to readers and to give a future checker (see {@code APMLP-1645}) something to verify
 * -- that no {@code @BackgroundOnly} code is reachable from a foreground call site. The discipline
 * it names is <b>not yet enforced</b>; hold to it by hand until the checker lands.
 *
 * <p>The two markers are <b>not symmetric</b> -- see {@link ForegroundSafe} for why it, not this
 * one, is the strictly stronger guarantee.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): every method of this type is background-only
 * unless a method-level {@link ForegroundSafe} widens it.
 *
 * <p><b>On a method</b> ({@link ElementType#METHOD}): this method specifically is background-only,
 * regardless of what the enclosing type declares -- a method-level marker always wins over the
 * type-level one.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BackgroundOnly {}
