package datadog.perfcontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field whose value must be retained for the life of the program to share its setup cost
 * across uses. A cache rebuilt with each request repeats that cost.
 *
 * <p>The name echoes Rust's {@code 'static} lifetime rather than the Java keyword {@code static}:
 * the property is "lives for the whole program," which a {@code final} field on a {@link Singleton}
 * satisfies without being {@code static}.
 *
 * <p>This marker changes no runtime behavior. The declaration rules below guide AI review; no
 * static checker currently enforces them.
 *
 * <p><b>Checker contract.</b> Inspect only fields annotated {@code @StaticLifetime}:
 *
 * <ul>
 *   <li><b>Accepted:</b> a {@code static final} field, or a {@code final} instance field declared
 *       on a class annotated {@link Singleton}. The singleton declaration is trusted, not verified.
 *   <li><b>Violation:</b> any other annotated field. This includes fields without {@code final} and
 *       instance fields on classes without {@code @Singleton}, even if a class is constructed only
 *       once per session.
 *   <li><b>Out of scope:</b> unannotated fields, how often a value is reused, and replacement of
 *       state inside the referenced object. The check does not follow references through the object
 *       graph.
 * </ul>
 *
 * <p>Requiring {@code final} prevents reassignment from discarding cached state and repeating
 * setup. It does not prevent the referenced object from changing its own state.
 *
 * <p>A shared {@link java.lang.ClassValue} satisfies the same field rules. Its values are cached
 * per class; computation may be repeated under races or after {@link
 * java.lang.ClassValue#remove(Class) remove}. This contract covers the shared holder, not the
 * lifetime of each cached value.
 *
 * <p>Violation examples: an instance cache on a class without {@code @Singleton}, or a static cache
 * without {@code final}.
 *
 * <pre>
 * &#64;StaticLifetime
 * private final DDCache&lt;String, String&gt; instanceCache = DDCaches.newFixedSizeCache(128);
 *
 * &#64;StaticLifetime
 * private static DDCache&lt;String, String&gt; staticCache = DDCaches.newFixedSizeCache(128);
 * </pre>
 *
 * <p>Compliant examples:
 *
 * <pre>
 * &#64;StaticLifetime
 * private static final DDCache&lt;String, String&gt; CACHE = DDCaches.newFixedSizeCache(128);
 *
 * &#64;Singleton class Registry {
 *   &#64;StaticLifetime
 *   private final DDCache&lt;String, String&gt; cache = DDCaches.newFixedSizeCache(128);
 * }
 * </pre>
 */
@Documented
@PerfContract
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface StaticLifetime {}
