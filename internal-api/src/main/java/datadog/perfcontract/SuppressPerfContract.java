package datadog.perfcontract;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Suppresses findings from one or more annotations marked with {@link PerfContract} on the
 * annotated declaration. Suppressions document deliberate exceptions and have no runtime effect.
 *
 * <p>{@link #value} identifies contracts by class, so stale names fail to compile. {@link #reason}
 * records why the exception is intentional and must not be blank.
 *
 * <p>This annotation may also annotate another annotation type to define a reusable suppression.
 * Applying that annotation is equivalent to applying {@code @SuppressPerfContract} directly and
 * uses the meta-annotation's reason. Prefer direct use for declaration-specific reasons. Do not
 * suppress a case that the contract already defines as compliant.
 *
 * <p>The target excludes local variables because declaration annotations on them are not stored in
 * class files. {@link ElementType#ANNOTATION_TYPE} enables reusable suppressions.
 *
 * <p>{@link RetentionPolicy#CLASS} makes suppressions visible to classfile-based tools without
 * exposing them through runtime reflection.
 *
 * <p><b>Checker contract.</b> A finding is suppressed when the declaration has either:
 *
 * <ul>
 *   <li>this annotation with the relevant contract in {@link #value} and a non-blank {@link
 *       #reason}; or
 *   <li>an annotation that carries such a {@code @SuppressPerfContract} annotation.
 * </ul>
 *
 * <p>Every class in {@code value} must itself be annotated {@link PerfContract}; otherwise the
 * suppression is invalid. A terse reason is valid if it is non-blank.
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
