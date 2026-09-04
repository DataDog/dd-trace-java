package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type that should never be <em>retained</em> -- never assigned to a field, put into a
 * collection, or cached -- though it may otherwise flow normally through ordinary code: returned
 * from a method, passed to a callback, chained through further calls. The line is storage, not how
 * far the value travels: a {@code @NoEscape} value can cross many methods and frames as long as
 * nothing along the way parks it somewhere that outlives the operation using it.
 *
 * <p>"Should", in the RFC-2119 sense: retaining an instance is presumed wrong and needs a reason,
 * not an absolute prohibition. A deliberate, reviewed exception -- e.g. a container that retains a
 * {@code @NoEscape} value as a precaution and has weighed the tradeoff -- is legitimate as long as
 * it is called out at the retention site (e.g. a comment explaining why) rather than done silently.
 *
 * <p>Two motivating shapes, both real for existing types in this codebase:
 *
 * <ul>
 *   <li><b>Shared-backing view.</b> A type that shares backing storage with something it was
 *       derived from (e.g. a substring view that shares its parent {@code String}'s backing array)
 *       to avoid a copy. Storing an instance pins the entire backing object alive for as long as
 *       the view is retained, turning an allocation-avoidance trick into a memory leak the moment
 *       it is kept around rather than consumed and dropped.
 *   <li><b>Escape-analysis-dependent value.</b> A type deliberately shaped to scalar-replace under
 *       escape analysis rather than actually allocate, on the assumption that it is constructed,
 *       consumed, and discarded rather than stored. Assigning an instance to a field or collection
 *       forces the JIT to materialize it as a real, permanent allocation, defeating the reason the
 *       type exists -- even though passing the same instance through several method calls first is
 *       fine.
 * </ul>
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * constraint to readers and to give a future checker something to verify -- that a field (instance
 * or static) declared with a {@code @NoEscape} type has a reason to be there. The discipline it
 * names is <b>not yet enforced</b>; hold to it by hand until the checker lands.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): instances of this type should not be stored in a
 * field or collection. Returning one, passing it to a callback, or chaining further calls on it is
 * fine -- what needs a reason is anything that keeps it alive past the operation using it.
 *
 * <p><b>Checker contract.</b> The rule below is written to be machine-checkable -- by a future
 * static checker, or in the meantime by an AI reviewer (see the perf-review skill's {@code
 * checks.md}) -- without needing to read this class's prose above. Because the underlying rule is
 * "should" rather than "must", a trigger is a presumptive finding to raise, not an automatic
 * failure: a field that carries a comment explaining the deliberate exception is compliant.
 *
 * <ul>
 *   <li><b>Trigger:</b> a field (instance or static, in any class) whose declared type is annotated
 *       {@code @NoEscape}, either directly (e.g. {@code SubSequence field;}) or as a generic type
 *       argument of the field's declared type (e.g. {@code List<SubSequence>}, {@code Map<K,
 *       Maybe<V>>}), with no comment at the declaration explaining why the retention is safe.
 *   <li><b>Not a trigger:</b> a local variable, a method parameter, or a method return type -- this
 *       rule flags <em>storage</em> that outlives the call, not ordinary use within it. Also not a
 *       trigger: the same field shape, annotated with a comment justifying the retention.
 *   <li><b>Violation example:</b> {@code private final SubSequence cached;}
 *   <li><b>Compliant example:</b> {@code private final String cached;} -- materialize the view
 *       (e.g. call {@code toString()}) before storing it. Or, if retention is a deliberate,
 *       reviewed exception: {@code // Retained on purpose: <reason>} above the field.
 *   <li><b>Out of scope (v1):</b> escape through a non-generic/raw container, a capturing lambda,
 *       or a returned value the caller goes on to store. Flag only the field-declaration shape
 *       above; widen this contract only once a real case proves it insufficient, rather than
 *       guessing ahead of one.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NoEscape {}
