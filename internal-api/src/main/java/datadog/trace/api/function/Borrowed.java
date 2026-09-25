package datadog.trace.api.function;

import datadog.perfcontract.SuppressPerfContract;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field that retains an {@link NoEscape} value on purpose: the field borrows the value for
 * a bounded, non-escaping scope of its own (e.g. a container's lifetime is itself tied to a single
 * operation), rather than accidentally defeating the reason the {@code @NoEscape} type exists.
 *
 * <p>This is a <b>canned exception</b> -- a named, reusable stand-in for
 * {@code @SuppressPerfContract(value = NoEscape.class, reason = "...")}, for the specific,
 * recurring shape where the justification is always the same one-line story: "this field's own
 * scope is bounded the same way the value's would have been, so retaining it here isn't the escape
 * the marker warns about." Reach for {@code @SuppressPerfContract} directly instead when the
 * justification is situation-specific rather than this canned story.
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior.
 *
 * <p><b>On a field</b> ({@link ElementType#FIELD}): satisfies {@link NoEscape}'s Checker contract
 * for that field, the same as a directly-applied {@code @SuppressPerfContract(value =
 * NoEscape.class, ...)} or a {@code // Retained on purpose: <reason>} comment would.
 *
 * <p><b>Checker contract.</b> See {@link SuppressPerfContract}'s Checker contract for the general
 * rule this instantiates. Concretely:
 *
 * <ul>
 *   <li><b>Not a trigger:</b> a field that would otherwise trigger {@link NoEscape}'s field-storage
 *       check, but is annotated {@code @Borrowed}.
 *   <li><b>Compliant example:</b> {@code @Borrowed private final SubSequence view;}
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
@SuppressPerfContract(
    value = NoEscape.class,
    reason = "field's own scope is bounded the same way the borrowed value's would have been")
public @interface Borrowed {}
