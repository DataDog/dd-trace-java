package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.Taintable.Source;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Main API for propagation of tainted values, */
@SuppressWarnings("unused")
public interface PropagationModule extends IastModule {

  /** @see #taint(IastContext, Object, byte) */
  void taint(@Nullable Object target, byte origin);

  /**
   * Taints the object with a source with the selected origin and no name, if target is a char
   * sequence it will be used as value
   */
  void taint(@Nullable IastContext ctx, @Nullable Object target, byte origin);

  /** @see #taint(IastContext, Object, byte, CharSequence) */
  void taint(@Nullable Object target, byte origin, @Nullable CharSequence name);

  /**
   * Taints the object with a source with the selected origin and name, if target is a char sequence
   * it will be used as value
   */
  void taint(
      @Nullable IastContext ctx, @Nullable Object target, byte origin, @Nullable CharSequence name);

  /** @see #taint(IastContext, Object, byte, CharSequence, Object) */
  void taint(
      @Nullable Object target, byte origin, @Nullable CharSequence name, @Nullable Object value);

  /** Taints the object with a source with the selected origin, name and value */
  void taint(
      @Nullable IastContext ctx,
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /** @see #taintIfTainted(IastContext, Object, Object) */
  void taintIfTainted(@Nullable Object target, @Nullable Object input);

  /**
   * Taints the object only if the input value is tainted. If tainted, it will use the highest
   * priority source of the input to taint the object.
   */
  void taintIfTainted(@Nullable IastContext ctx, @Nullable Object target, @Nullable Object input);

  /** @see #taintIfTainted(IastContext, Object, Object, boolean, int) */
  void taintIfTainted(
      @Nullable Object target, @Nullable Object input, boolean keepRanges, int mark);

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
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      boolean keepRanges,
      int mark);

  /** @see #taintIfTainted(IastContext, Object, Object, byte) */
  void taintIfTainted(@Nullable Object target, @Nullable Object input, byte origin);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and no name, if target is a char sequence it will be used as
   * value
   */
  void taintIfTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object input, byte origin);

  /** @see #taintIfTainted(IastContext, Object, Object, byte, CharSequence) */
  void taintIfTainted(
      @Nullable Object target, @Nullable Object input, byte origin, @Nullable CharSequence name);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and name, if target is a char sequence it will be used as
   * value
   */
  void taintIfTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name);

  /** @see #taintIfTainted(IastContext, Object, Object, byte, CharSequence, Object) */
  void taintIfTainted(
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin, name and value.
   */
  void taintIfTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /** @see #taintIfAnyTainted(IastContext, Object, Object[]) */
  void taintIfAnyTainted(@Nullable Object target, @Nullable Object[] inputs);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintIfTainted(IastContext, Object, Object)}
   *
   * @see #taintIfTainted(IastContext, Object, Object)
   */
  void taintIfAnyTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object[] inputs);

  /** @see #taintIfAnyTainted(IastContext, Object, Object[], boolean, int) */
  void taintIfAnyTainted(
      @Nullable Object target, @Nullable Object[] inputs, boolean keepRanges, int mark);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintIfTainted(IastContext, Object, Object, boolean, int)}
   *
   * @see #taintIfTainted(IastContext, Object, Object, boolean, int)
   */
  void taintIfAnyTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object[] inputs,
      boolean keepRanges,
      int mark);

  /** @see #taintDeeply(IastContext, Object, byte, Predicate) */
  int taintDeeply(@Nullable Object target, byte origin, Predicate<Class<?>> classFilter);

  /**
   * Visit the graph of the object and taints all the string properties found using a source with
   * the selected origin and no name.
   *
   * @param classFilter filter for types that should be included in the visiting process
   * @return number of tainted elements
   */
  int taintDeeply(
      @Nullable IastContext ctx,
      @Nullable Object target,
      byte origin,
      Predicate<Class<?>> classFilter);

  /** @see #isTainted(IastContext, Object) */
  boolean isTainted(@Nullable Object target);

  /** Checks if an arbitrary object is tainted */
  boolean isTainted(@Nullable IastContext ctx, @Nullable Object target);

  /** @see #findSource(IastContext, Object) */
  @Nullable
  Source findSource(@Nullable Object target);

  /**
   * Returns the source with the highest priority if the object is tainted, {@code null} otherwise
   */
  @Nullable
  Source findSource(@Nullable IastContext ctx, @Nullable Object target);
}
