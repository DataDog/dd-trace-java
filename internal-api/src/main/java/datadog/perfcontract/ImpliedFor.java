package datadog.perfcontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a {@link PerfContract} to types that cannot carry it themselves, such as JDK or
 * third-party classes. Annotate the contract annotation with the types its rule is implied for.
 *
 * <p>A contract is normally opt-in: it guards only declarations that carry it. Some types have the
 * contract by their nature. A non-{@code static} {@link ClassValue} field, for example, discards
 * its per-class cache with each instance, yet nobody annotates the broken field. Listing {@code
 * ClassValue} here lets tools check every such field.
 *
 * <p>This marker changes no runtime behavior. {@link RetentionPolicy#CLASS} lets tools read the
 * implied types from the contract's class file, like the contract itself.
 *
 * <p><b>Checker contract.</b> For a contract annotation carrying {@code @ImpliedFor}:
 *
 * <ul>
 *   <li><b>Implied fields:</b> a field whose declared type is a listed type, or a subtype of one,
 *       is checked as if it carried the contract. Only field contracts use this so far.
 *   <li><b>Suppression:</b> {@link SuppressPerfContract} exempts an implied finding exactly as it
 *       exempts an explicit one.
 *   <li><b>Invalid:</b> {@code @ImpliedFor} on an annotation that is not itself annotated {@link
 *       PerfContract}.
 * </ul>
 *
 * <p>Add a type only when a real case shows that every declaration of it needs the contract.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ImpliedFor {

  /** The types the annotated contract is implied for. */
  Class<?>[] value();
}
