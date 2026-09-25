package datadog.trace.api.function;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Suppresses a specific {@link PerfContract} marker's finding on the field, parameter, constructor,
 * method, or type this decorates -- a deliberate, reviewed exception, not a silent override.
 *
 * <p>Modeled on {@code @SuppressWarnings}, with one deliberate difference: {@link #reason} is
 * mandatory, not optional. A perf-contract marker's discipline is a project-specific, unverified
 * claim (see each marker's own "v1 posture" note), not a compiler-known warning category -- so
 * unlike a compiler warning, the exception needs its own justification on record, not just a
 * category name.
 *
 * <p>{@link #value} names the marker(s) being suppressed <i>by class reference</i> (e.g. {@code
 * StaticLifetime.class}), not by string, so a rename is caught at compile time instead of silently
 * going stale.
 *
 * <p>This is a generic replacement for a per-marker exemption channel (e.g. a bespoke nested
 * annotation on one marker, or a comment-based convention) -- one mechanism every current and
 * future perf-contract marker can share, instead of each reinventing its own.
 *
 * <p><b>Meta-annotation use.</b> This annotation may also be placed on another annotation
 * <i>type</i> ({@link ElementType#ANNOTATION_TYPE}), turning that annotation into a named, canned
 * exception for the marker(s) in {@link #value} -- e.g. a hypothetical {@code @Borrowed}, meta-
 * annotated {@code @SuppressPerfContract(value = NoEscape.class, reason = "reference is borrowed
 * for a bounded, non-escaping scope")}. A declaration carrying {@code @Borrowed} is then treated
 * the same as one carrying {@code @SuppressPerfContract} directly, using the meta-annotation's own
 * {@link #reason} -- callers don't restate it at each use site. Reach for this when a marker keeps
 * accumulating the same one-line justification at many call sites; a fresh, situation-specific
 * justification is better spelled out directly.
 *
 * <p>This is <i>not</i> the right tool for a structural precondition that a marker's own Checker
 * contract accepts as satisfying the contract outright (e.g. {@link Singleton} for {@link
 * StaticLifetime}) -- those aren't exceptions being waved through, they're a case where there is no
 * finding to begin with. Reserve meta-annotation use for genuine "yes, this would otherwise be
 * flagged, and that's deliberate" cases.
 *
 * <p><b>Target.</b> The element types below are every {@code ElementType} a perf-contract marker in
 * this package targets today or plausibly could, intersected with the {@code ElementType}s a
 * classfile-only checker can actually observe -- {@code LOCAL_VARIABLE} and a few others are
 * excluded because declaration annotations on them are not retained in the classfile at all,
 * regardless of {@link RetentionPolicy}, so allowing them here would silently do nothing. {@code
 * ANNOTATION_TYPE} is included for meta-annotation use, above.
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior.
 *
 * <p><b>Checker contract.</b> A finding that a {@link PerfContract} marker's own Checker contract
 * would otherwise raise on this declaration is not a violation when either:
 *
 * <ul>
 *   <li>this annotation is present on the same declaration with that marker's class among {@link
 *       #value}, and {@link #reason} is non-blank; or
 *   <li>an annotation that is itself meta-annotated {@code @SuppressPerfContract} with that
 *       marker's class among its {@link #value} is present on the same declaration -- treat that
 *       meta-annotation's {@link #reason} as satisfying the reason requirement.
 * </ul>
 *
 * <ul>
 *   <li><b>Trigger:</b> {@code value} contains a class that is not annotated {@link PerfContract}
 *       -- names a marker this mechanism doesn't govern, or a stale reference to a marker that was
 *       removed. This is a defect in the suppression itself, not something a v1 checker can always
 *       catch: see "Out of scope" below.
 *   <li><b>Not a trigger:</b> {@code reason} being terse -- there is no minimum length or format
 *       requirement in v1, only that it is present and non-blank.
 *   <li><b>Not a trigger:</b> a declaration carrying a canned-exception annotation (see
 *       "Meta-annotation use" above) instead of {@code @SuppressPerfContract} directly.
 *   <li><b>Compliant example:</b> {@code @SuppressPerfContract(value = StaticLifetime.class, reason
 *       = "held on a request-scoped object by design; see JIRA-1234") private final DDCache<K, V>
 *       cache = DDCaches.newFixedSizeCache(128);}
 *   <li><b>Compliant example (meta-annotation use):</b> {@code @Borrowed private final SubSequence
 *       view;}, where {@code @Borrowed} is itself meta-annotated {@code @SuppressPerfContract(value
 *       = NoEscape.class, reason = "...")}.
 *   <li><b>Out of scope (v1):</b> verifying, from a classfile-only checker, that every class named
 *       in {@code value} is annotated {@link PerfContract} -- the same limitation
 *       {@code @SuppressWarnings} has toward its own string categories, just typed instead of
 *       stringly.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
  ElementType.TYPE,
  ElementType.FIELD,
  ElementType.METHOD,
  ElementType.CONSTRUCTOR,
  ElementType.PARAMETER,
  ElementType.ANNOTATION_TYPE
})
public @interface SuppressPerfContract {

  /** The perf-contract marker(s) being suppressed, by class reference. */
  Class<? extends Annotation>[] value();

  /** Why this exception is deliberate and reviewed, not an oversight. */
  String reason();
}
