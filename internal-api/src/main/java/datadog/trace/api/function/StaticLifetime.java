package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field that must live for the whole program, not merely for the lifetime of its enclosing
 * instance -- typically a cache or other expensive object whose entire value comes from being
 * amortized across many uses, which a per-instance field can silently defeat if that instance
 * itself doesn't live for the whole program.
 *
 * <p>The name deliberately echoes Rust's {@code 'static} lifetime rather than the Java keyword
 * {@code static} itself: the property this enforces is "lives for the whole program." A {@link
 * java.lang.ClassValue} is the motivating case for that distinction -- each per-{@code Class} value
 * it computes lives for the whole program without itself being declared in a {@code static} field
 * anywhere, because {@code ClassValue} does its own process-wide caching internally. The holder
 * field that points at the {@code ClassValue} instance still needs to be {@code static final} --
 * see the checker contract below -- it's only the individual per-{@code Class} values inside it
 * that get their process-wide lifetime for free.
 *
 * <p>Motivating defect: a cache constructed per-request, as an instance field on a per-request
 * object, instead of once as a {@code static} field -- so the cache was allocated and thrown away
 * on every request and never actually amortized anything. It compiled, ran, and passed tests while
 * quietly defeating the entire point of caching -- the same silent-failure shape as the other
 * perf-contract annotations in this package.
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * constraint to readers and to give a future checker something to verify. The discipline it names
 * is <b>not yet enforced</b>; hold to it by hand until the checker lands.
 *
 * <p><b>On a field</b> ({@link ElementType#FIELD}): the field's value must actually live for the
 * whole program, not just for as long as its enclosing instance happens to.
 *
 * <p><b>Checker contract.</b> The rule below is written to be machine-checkable -- by a future
 * static checker, or in the meantime by an AI reviewer -- without needing to read this class's
 * prose above. This is a declaration-scan check: it looks at how the field is declared, not at
 * whether it is, in practice, reused enough to be worth its cost, and it does not attempt to find
 * unannotated per-instance caches -- it only guards fields that opt in.
 *
 * <ul>
 *   <li><b>Accepted (satisfies the contract):</b> a {@code static final} field of the cache /
 *       expensive-object type; a {@code static final} {@link java.lang.ClassValue}{@code <T>} used
 *       for a per-class one-shot computation; or a {@code final} instance field, on a class
 *       annotated {@link Singleton}, whose enclosing instance is thereby guaranteed to be
 *       process-wide. {@code final} is required in both the static and singleton-scoped-instance
 *       shapes: a reassignable field can be replaced with a fresh instance at any point, silently
 *       discarding everything amortized in the old one -- the same defeat this annotation exists to
 *       catch, just triggered by a write instead of by scope.
 *   <li><b>Trigger (violation, scope):</b> an instance field of the annotated type on a class that
 *       is <em>not</em> annotated {@link Singleton} -- even if, in practice, the class is only ever
 *       constructed once per logical "session". This check is declaration-local; it does not
 *       attempt to prove single construction dynamically, so a plain instance field never satisfies
 *       the contract on its own.
 *   <li><b>Trigger (violation, mutability):</b> a {@code static} field of the annotated type, or an
 *       instance field on a class annotated {@link Singleton}, that is not also {@code final} --
 *       reassignable, so nothing stops a fresh instance from silently replacing the amortized one
 *       at runtime, regardless of scope.
 *   <li><b>Violation example (scope):</b> {@code private final DDCache<K, V> cache =
 *       DDCaches.newFixedSizeCache(128);} as an instance field of a class constructed per-request,
 *       per-call, or otherwise more than once for the life of the process.
 *   <li><b>Violation example (mutability):</b> {@code private static DDCache<K, V> cache =
 *       DDCaches.newFixedSizeCache(128);} -- {@code static} but not {@code final}, so any code with
 *       write access can swap in a fresh, cold cache and discard everything amortized in the old
 *       one.
 *   <li><b>Compliant example (static):</b> {@code private static final DDCache<K, V> CACHE =
 *       DDCaches.newFixedSizeCache(128);}
 *   <li><b>Compliant example (singleton-scoped instance):</b> {@code @Singleton class Registry {
 *       private final DDCache<K, V> cache = DDCaches.newFixedSizeCache(128); }}
 *   <li><b>Out of scope (v1):</b> proving a cache is reused enough to be worth its allocation cost;
 *       catching a non-static cache that isn't annotated with this marker; a {@code final} field
 *       whose referent is itself internally mutable/replaceable (e.g. wraps its state in an {@code
 *       AtomicReference} it swaps) -- {@code final} is checked on the field, not transitively
 *       through the object graph. Widen this contract only once a real case proves it insufficient,
 *       rather than guessing ahead of one.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface StaticLifetime {}
