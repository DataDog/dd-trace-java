package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.Taintable.Source;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Main API for propagation of tainted values, */
@SuppressWarnings("unused")
public interface PropagationModule extends IastModule {

  /**
   * Taints the object with a source with the selected origin and no name, if target is a char
   * sequence it will be used as value
   */
  void taint(@Nullable Object target, byte origin);

  /**
   * Taints the object with a source with the selected origin and name, if target is a char sequence
   * it will be used as value
   */
  void taint(@Nullable Object target, byte origin, @Nullable CharSequence name);

  /** Taints the object with a source with the selected origin, name and value */
  void taint(
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name,
      @Nullable CharSequence value);

  /**
   * Taints the object only if the input value is tainted. If tainted, it will use the highest
   * priority source of the input to taint the object.
   */
  void taintIfTainted(@Nullable Object target, @Nullable Object input);

  /**
   * Taints the object only if the input value is tainted. It will try to reuse sources from the
   * input value according to:
   *
   * <ul>
   *   <li>keepRanges=true will reuse the ranges from the input tainted value and mark them
   *   <li>keepRanges=false will use the highest priority source from the input ranges and mark it
   * </ul>
   */
  void taintIfTainted(
      @Nullable Object target, @Nullable Object input, boolean keepRanges, int mark);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and no name, if target is a char sequence it will be used as
   * value
   */
  void taintIfTainted(@Nullable Object target, @Nullable Object input, byte origin);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and name, if target is a char sequence it will be used as
   * value
   */
  void taintIfTainted(
      @Nullable Object target, @Nullable Object input, byte origin, @Nullable CharSequence name);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin, name and value.
   */
  void taintIfTainted(
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable CharSequence value);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintIfTainted(Object, Object)}
   *
   * @see #taintIfTainted(Object, Object)
   */
  void taintIfAnyTainted(@Nullable Object target, @Nullable Object[] inputs);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintIfTainted(Object, Object, boolean, int)}
   *
   * @see #taintIfTainted(Object, Object, boolean, int)
   */
  void taintIfAnyTainted(
      @Nullable Object target, @Nullable Object[] inputs, boolean keepRanges, int mark);

  /**
   * Visit the graph of the object and taints all the string properties found using a source with
   * the selected origin and no name.
   *
   * @param classFilter filter for types that should be included in the visiting process
   */
  void taintDeeply(@Nullable Object target, byte origin, Predicate<Class<?>> classFilter);

  /** Checks if an arbitrary object is tainted */
  boolean isTainted(@Nullable Object target);

  /**
   * Returns the source with the highest priority if the object is tainted, {@code null} otherwise
   */
  @Nullable
  Source findSource(@Nullable Object target);
}
