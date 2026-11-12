package datadog.perfcontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a {@link PerfContract} to fields of types that cannot carry the contract themselves, such
 * as JDK or third-party types. Apply this annotation to the contract annotation.
 *
 * <p>For example, creating a new {@link ClassValue} for each short-lived owner prevents later
 * owners from reusing its cached values. An implied contract lets tools check those fields without
 * an explicit contract annotation.
 *
 * <p>This annotation changes no runtime behavior. {@link RetentionPolicy#CLASS} makes the implied
 * types available to tools reading the contract's class file.
 *
 * <p><b>Checker contract.</b> For an annotation carrying {@code @ImpliedFor}:
 *
 * <ul>
 *   <li><b>Implied fields:</b> fields whose declared type is listed in {@link #value}, or is a
 *       subtype of a listed type, are checked as if they carried the contract. Only field contracts
 *       are currently covered.
 *   <li><b>Suppression:</b> {@link SuppressPerfContract} exempts implied findings under the same
 *       rules as explicit findings.
 *   <li><b>Invalid:</b> the annotation carrying {@code @ImpliedFor} is not itself annotated {@link
 *       PerfContract}.
 * </ul>
 *
 * <p>Add a type only when a real case shows that its field declarations require the contract.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ImpliedFor {

  /** The types the annotated contract is implied for. */
  Class<?>[] value();
}
