package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code cheap enough to call from an application thread (the foreground) -- the request or
 * transaction thread the instrumented application itself is running, where any added cost is
 * customer-visible latency, as opposed to a background thread the tracer owns and paces itself (see
 * {@link BackgroundOnly}).
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * guarantee to readers and to give a future checker (see {@code APMLP-1645}) something to verify --
 * that no {@link BackgroundOnly} code is reachable from a foreground call site. The discipline it
 * names is <b>not yet enforced</b>; hold to it by hand until the checker lands.
 *
 * <p>The two markers are <b>not symmetric</b>. {@code @ForegroundSafe} is the strictly stronger
 * guarantee: code cheap enough for the foreground is automatically fine to call from a background
 * thread too, so a {@code @ForegroundSafe} type or method may be called from either. {@link
 * BackgroundOnly} code carries no such guarantee and must never be reached from a foreground call
 * site.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): every method of this type is foreground-safe
 * unless a method-level {@link BackgroundOnly} narrows it.
 *
 * <p><b>On a method</b> ({@link ElementType#METHOD}): this method specifically is foreground-safe,
 * regardless of what the enclosing type declares -- a method-level marker always wins over the
 * type-level one.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ForegroundSafe {}
