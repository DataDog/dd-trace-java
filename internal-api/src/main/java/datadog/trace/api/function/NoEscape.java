package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transient, zero-copy view type meant to live only within a single call frame -- it must
 * not be stored in a field, a collection, a cache, or anywhere else that outlives the call that
 * produced it.
 *
 * <p>A type like this typically shares backing storage with something it was derived from (e.g. a
 * substring view that shares its parent {@code String}'s backing array) to avoid a copy. That
 * sharing is exactly why it must not escape: holding an instance can pin the entire backing object
 * alive for as long as the view survives, turning an allocation-avoidance trick into a memory leak
 * the moment it outlives the frame it was built for.
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * constraint to readers and to give a future checker (see {@code APMLP-1787}) something to verify
 * -- that no field (instance or static) is declared with a {@code @NoEscape} type. The discipline
 * it names is <b>not yet enforced</b>; hold to it by hand until the checker lands.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): instances of this type must not escape the call
 * frame that created them -- do not assign one to a field, put one in a collection, or return one
 * to a caller that might hold onto it beyond the current call.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NoEscape {}
