package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transient type meant to live only within a single call frame -- it must not be stored in
 * a field, a collection, a cache, or anywhere else that outlives the call that produced it.
 *
 * <p>Two motivating shapes, both real for existing types in this codebase:
 *
 * <ul>
 *   <li><b>Shared-backing view.</b> A type that shares backing storage with something it was
 *       derived from (e.g. a substring view that shares its parent {@code String}'s backing array)
 *       to avoid a copy. Holding an instance can pin the entire backing object alive for as long as
 *       the view survives, turning an allocation-avoidance trick into a memory leak the moment it
 *       outlives the frame it was built for.
 *   <li><b>Escape-analysis-dependent value.</b> A type deliberately shaped to scalar-replace under
 *       escape analysis rather than actually allocate, on the assumption that it is constructed,
 *       used, and discarded within one call frame. Storing an instance anywhere it survives past
 *       that frame forces the JIT to materialize it as a real, permanent allocation, defeating the
 *       reason the type exists.
 * </ul>
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
