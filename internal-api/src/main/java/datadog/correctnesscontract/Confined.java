package datadog.correctnesscontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares where an instance of the annotated type remains valid. Using an instance outside its
 * {@link Extent} is a bug: the type relies on that confinement for its own correctness, for example
 * by completing through an unsynchronized flag or by carrying state that is only meaningful during
 * one call.
 *
 * <p>Extents are ordered from narrowest to widest: {@link Extent#CALL}, then {@link Extent#FRAME}.
 * Both imply the instance is never used from another thread. An unannotated type is unconfined.
 *
 * <p>This marker changes no runtime behavior. The checker contract below guides AI review; no
 * static checker currently enforces it. As a {@link CorrectnessContract}, it has no exemption.
 *
 * <p>Compare {@link datadog.trace.api.function.NoEscape}, which says an instance <i>should</i> not
 * be retained, for performance. A confined instance must not be retained.
 *
 * <p><b>Checker contract.</b> Applies to production source sets. Tests and benchmarks that misuse a
 * type deliberately, to verify its behavior, are outside the rule.
 *
 * <ul>
 *   <li><b>Violation, every extent:</b> storing an instance in a field or collection, or capturing
 *       it in a lambda or anonymous class.
 *   <li><b>Violation, {@code CALL}:</b> returning an instance, except from the method that creates
 *       it.
 *   <li><b>Violation, {@code FRAME} on an {@link AutoCloseable} type:</b> obtaining an instance
 *       anywhere but as a try-with-resources resource, unless the obtaining method returns it. The
 *       caller it is returned to inherits the obligation.
 *   <li><b>Accepted:</b> local variables, synchronous calls that receive the instance as an
 *       argument, a creating method returning its new instance, and returning a {@code FRAME}
 *       instance to the caller.
 *   <li><b>Out of scope (v1):</b> completion of a {@code FRAME} instance that is not {@link
 *       AutoCloseable}, escape through raw containers, and values stored several calls after they
 *       were obtained. Widen this contract only when a real case shows it is insufficient.
 * </ul>
 */
@Documented
@CorrectnessContract
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Confined {

  /** The extent instances of the annotated type are confined to. */
  Extent value();

  /** Where a confined instance remains valid, from narrowest to widest. */
  enum Extent {
    /** Valid only for the duration of the call it was passed into. */
    CALL,

    /**
     * Valid only in the frame that obtained it and the calls that frame makes, and completed on
     * every path before that frame exits.
     */
    FRAME
  }
}
