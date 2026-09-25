package datadog.perfcontract;

import datadog.trace.api.function.NoEscape;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation marking an annotation as a <b>perf-contract marker</b>: a
 * documentation-and-tooling annotation, like {@link NoEscape}, that names a performance discipline
 * without changing behavior, and whose findings {@link SuppressPerfContract} can suppress.
 *
 * <p>This carries no rule of its own. It exists so tooling -- a future static checker, or an AI
 * reviewer in the meantime -- has one place to discover "which annotations here are perf-contract
 * markers" rather than a hardcoded list that drifts as new markers are added.
 *
 * <p><b>On an annotation type</b> ({@link ElementType#ANNOTATION_TYPE}): the annotated annotation
 * is a perf-contract marker, and {@code @SuppressPerfContract(TheMarker.class, reason = "...")} is
 * a valid way to suppress a finding it would otherwise raise on the same declaration.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface PerfContract {}
