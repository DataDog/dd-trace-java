package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.taint.Source;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Main API for propagation of tainted values, */
public interface PropagationModule extends IastModule {

  /**
   * Taints the object with a source with the selected origin and no name, if target is a char
   * sequence it will be used as value
   */
  void taintObject(@Nullable TaintedObjects to, @Nullable Object target, byte origin);

  /**
   * Taints the object with a source with the selected origin and name, if target is a char sequence
   * it will be used as value
   */
  void taintObject(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name);

  /** Taints the object with a source with the selected origin, name and value */
  void taintObject(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * Taints the object with a source with the selected origin, range and no name. If target is a
   * char sequence it will be used as value.
   *
   * <p>If the value is already tainted this method will append a new range.
   */
  void taintObjectRange(
      @Nullable TaintedObjects to, @Nullable Object target, byte origin, int start, int length);

  /**
   * Taints the object only if the input value is tainted. If tainted, it will use the highest
   * priority source of the input to taint the object.
   */
  void taintObjectIfTainted(
      @Nullable TaintedObjects to, @Nullable Object target, @Nullable Object input);

  /**
   * Taints the object only if the input value is tainted. It will try to reuse sources from the
   * input value according to:
   *
   * <ul>
   *   <li>keepRanges=true will reuse the ranges from the input tainted value and mark them
   *   <li>keepRanges=false will use the highest priority source from the input ranges and mark it
   * </ul>
   */
  void taintObjectIfTainted(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      @Nullable Object input,
      boolean keepRanges,
      int mark);

  /**
   * Taints the object only if the input value has a tainted range that intersects with the
   * specified range. It will try to reuse sources from the input value according to:
   *
   * <ul>
   *   <li>keepRanges=true will reuse the ranges from the intersection and mark them
   *   <li>keepRanges=false will use the highest priority source from the intersection and mark it
   * </ul>
   */
  void taintObjectIfRangeTainted(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      @Nullable Object input,
      int start,
      int length,
      boolean keepRanges,
      int mark);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and no name, if target is a char sequence it will be used as
   * value
   */
  void taintObjectIfTainted(
      @Nullable TaintedObjects to, @Nullable Object target, @Nullable Object input, byte origin);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and name, if target is a char sequence it will be used as
   * value
   */
  void taintObjectIfTainted(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin, name and value.
   */
  void taintObjectIfTainted(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintObjectIfTainted(TaintedObjects, Object, Object)}
   *
   * @see #taintObjectIfTainted(TaintedObjects, Object, Object)
   */
  void taintObjectIfAnyTainted(
      @Nullable TaintedObjects to, @Nullable Object target, @Nullable Object[] inputs);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintObjectIfTainted(TaintedObjects, Object, Object, boolean, int)}
   *
   * @see #taintObjectIfTainted(TaintedObjects, Object, Object, boolean, int)
   */
  void taintObjectIfAnyTainted(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      @Nullable Object[] inputs,
      boolean keepRanges,
      int mark);

  /**
   * Visit the graph of the object and taints all the string properties found using a source with
   * the selected origin and no name.
   *
   * @param classFilter filter for types that should be included in the visiting process
   * @return number of tainted elements
   */
  int taintObjectDeeply(
      @Nullable TaintedObjects to,
      @Nullable Object target,
      byte origin,
      Predicate<Class<?>> classFilter);

  /** Checks if an arbitrary object is tainted */
  boolean isTainted(@Nullable TaintedObjects to, @Nullable Object target);

  /**
   * Returns the source with the highest priority if the object is tainted, {@code null} otherwise
   */
  @Nullable
  Source findSource(@Nullable TaintedObjects to, @Nullable Object target);
}
