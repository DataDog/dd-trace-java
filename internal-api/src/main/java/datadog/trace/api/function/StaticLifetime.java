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
 * {@code static} itself: the property this enforces is "lives for the whole program," and a {@link
 * java.lang.ClassValue}-backed holder satisfies that without carrying the literal {@code static}
 * modifier.
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
 *       for a per-class one-shot computation; or an instance field, on a class annotated {@link
 *       Singleton}, whose enclosing instance is thereby guaranteed to be process-wide.
 *   <li><b>Trigger (violation):</b> an instance field of the annotated type on a class that is
 *       <em>not</em> annotated {@link Singleton} -- even if, in practice, the class is only ever
 *       constructed once per logical "session". This check is declaration-local; it does not
 *       attempt to prove single construction dynamically, so a plain instance field never satisfies
 *       the contract on its own.
 *   <li><b>Violation example:</b> {@code private final DDCache<K, V> cache =
 *       DDCaches.newFixedSizeCache(128);} as an instance field of a class constructed per-request,
 *       per-call, or otherwise more than once for the life of the process.
 *   <li><b>Compliant example (static):</b> {@code private static final DDCache<K, V> CACHE =
 *       DDCaches.newFixedSizeCache(128);}
 *   <li><b>Compliant example (singleton-scoped instance):</b> {@code @Singleton class Registry {
 *       private final DDCache<K, V> cache = DDCaches.newFixedSizeCache(128); }}
 *   <li><b>Out of scope (v1):</b> proving a cache is reused enough to be worth its allocation cost;
 *       catching a non-static cache that isn't annotated with this marker. Widen this contract only
 *       once a real case proves it insufficient, rather than guessing ahead of one.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface StaticLifetime {}
