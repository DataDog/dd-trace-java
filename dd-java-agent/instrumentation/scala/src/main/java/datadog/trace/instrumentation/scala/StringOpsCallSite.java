package datadog.trace.instrumentation.scala;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import scala.collection.immutable.StringOps;
import scala.math.ScalaNumber;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    helpers = {
      ScalaJavaConverters.class,
      ScalaJavaConverters.JavaIterable.class,
      ScalaJavaConverters.JavaIterator.class
    })
public class StringOpsCallSite {

  @CallSite.After(
      "java.lang.String scala.collection.immutable.StringOps.format(scala.collection.Seq)")
  public static String afterInterpolation(
      @CallSite.This @Nonnull final StringOps stringOps,
      @CallSite.Argument(0) final scala.collection.Seq<?> params,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        final Object[] args = ScalaJavaConverters.toArray(params);
        // Unwrap Scala ScalaNumber types to their underlying Java representation
        // This replicates Scala's unwrapArg() behavior before calling String.format
        final Object[] unwrappedArgs = unwrapScalaNumbers(args);
        module.onStringFormat(stringOps.toString(), unwrappedArgs, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInterpolation threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String scala.collection.StringOps$.format$extension(java.lang.String, scala.collection.immutable.Seq)")
  public static String afterInterpolation(
      @CallSite.This final Object target, // CSI forces to include the target of the invoke
      @CallSite.Argument(0) @Nonnull final String pattern,
      @CallSite.Argument(1) final scala.collection.immutable.Seq<?> params,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        final Object[] args = ScalaJavaConverters.toArray(params);
        // Unwrap Scala ScalaNumber types to their underlying Java representation
        // This replicates Scala's unwrapArg() behavior before calling String.format
        final Object[] unwrappedArgs = unwrapScalaNumbers(args);
        module.onStringFormat(pattern, unwrappedArgs, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInterpolation threw", e);
      }
    }
    return result;
  }

  /**
   * Unwraps all Scala ScalaNumber types in an array to their underlying Java representations.
   *
   * <p>This method replicates Scala's unwrapArg behavior from StringLike.scala:
   *
   * <pre>
   * private def unwrapArg(arg: Any): AnyRef = arg match {
   *   case x: ScalaNumber => x.underlying
   *   case x => x.asInstanceOf[AnyRef]
   * }
   * </pre>
   *
   * <p>The conversion is:
   *
   * <ul>
   *   <li>scala.math.BigDecimal -> java.math.BigDecimal (via underlying())
   *   <li>scala.math.BigInt -> java.math.BigInteger (via underlying())
   *   <li>Other types -> unchanged
   * </ul>
   *
   * <p>This ensures String.format receives java.math.BigDecimal/BigInteger instead of
   * scala.math.BigDecimal/BigInt, preventing IllegalFormatConversionException while maintaining
   * correct format results and taint tracking.
   *
   * @param args the array of arguments to unwrap
   * @return a new array with ScalaNumber instances unwrapped, or the original array if no changes
   */
  @Nonnull
  private static Object[] unwrapScalaNumbers(@Nonnull final Object[] args) {
    boolean needsUnwrap = false;
    // First pass: check if any argument needs unwrapping
    for (final Object arg : args) {
      if (arg != null && isScalaNumber(arg)) {
        needsUnwrap = true;
        break;
      }
    }
    // If no unwrapping needed, return original array
    if (!needsUnwrap) {
      return args;
    }
    // Second pass: create new array with unwrapped values
    final Object[] unwrapped = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      unwrapped[i] = unwrapScalaNumber(args[i]);
    }
    return unwrapped;
  }

  /**
   * Checks if an object is a Scala ScalaNumber (BigDecimal or BigInt).
   *
   * @param arg the object to check
   * @return true if the object is a ScalaNumber instance
   */
  private static boolean isScalaNumber(@Nonnull final Object arg) {
    return arg instanceof scala.math.ScalaNumber;
  }

  /**
   * Unwraps a single Scala ScalaNumber to its underlying Java representation.
   *
   * @param arg the argument to unwrap (can be null)
   * @return the unwrapped value if ScalaNumber, otherwise the original value
   */
  @Nullable
  private static Object unwrapScalaNumber(@Nullable final Object arg) {
    if (arg == null) {
      return null;
    }
    if (arg instanceof scala.math.ScalaNumber) {
      return ((ScalaNumber) arg).underlying();
    }
    return arg;
  }
}
