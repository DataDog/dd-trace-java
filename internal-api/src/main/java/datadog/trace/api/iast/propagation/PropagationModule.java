package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.Taintable.Source;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Main API for propagation of tainted values, */
@SuppressWarnings("unused")
public interface PropagationModule extends IastModule {

  /**
   * @see #taintObject(IastContext, Object, byte)
   */
  void taintObject(@Nullable Object target, byte origin);

  /**
   * Taints the object with a source with the selected origin and no name, if target is a char
   * sequence it will be used as value
   */
  void taintObject(@Nullable IastContext ctx, @Nullable Object target, byte origin);

  /**
   * @see #taintString(IastContext, String, byte)
   */
  void taintString(@Nullable String target, byte origin);

  /**
   * @see #taintObject(IastContext, Object, byte)
   */
  void taintString(@Nullable IastContext ctx, @Nullable String target, byte origin);

  /**
   * Taints the object with a source with the selected origin and name, if target is a char sequence
   * it will be used as value
   */
  void taintObject(
      @Nullable IastContext ctx, @Nullable Object target, byte origin, @Nullable CharSequence name);

  /**
   * @see #taintObject(IastContext, Object, byte, CharSequence)
   */
  void taintObject(@Nullable Object target, byte origin, @Nullable CharSequence name);

  /**
   * @see #taintObject(IastContext, Object, byte, CharSequence)
   */
  void taintString(
      @Nullable IastContext ctx, @Nullable String target, byte origin, @Nullable CharSequence name);

  /**
   * @see #taintString(IastContext, String, byte, CharSequence)
   */
  void taintString(@Nullable String target, byte origin, @Nullable CharSequence name);

  /**
   * @see #taintObject(IastContext, Object, byte, CharSequence, Object)
   */
  void taintObject(
      @Nullable Object target, byte origin, @Nullable CharSequence name, @Nullable Object value);

  /** Taints the object with a source with the selected origin, name and value */
  void taintObject(
      @Nullable IastContext ctx,
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * @see #taintString(IastContext, String, byte, CharSequence, CharSequence)
   */
  void taintString(
      @Nullable String target,
      byte origin,
      @Nullable CharSequence name,
      @Nullable CharSequence value);

  /**
   * @see #taintObject(IastContext, Object, byte, CharSequence, Object)
   */
  void taintString(
      @Nullable IastContext ctx,
      @Nullable String target,
      byte origin,
      @Nullable CharSequence name,
      @Nullable CharSequence value);

  /**
   * @see #taintObjectRange(IastContext, Object, byte, int, int)
   */
  void taintObjectRange(@Nullable Object target, byte origin, int start, int length);

  /**
   * Taints the object with a source with the selected origin, range and no name. If target is a
   * char sequence it will be used as value.
   *
   * <p>If the value is already tainted this method will append a new range.
   */
  void taintObjectRange(
      @Nullable IastContext ctx, @Nullable Object target, byte origin, int start, int length);

  /**
   * @see #taintStringRange(IastContext, String, byte, int, int)
   */
  void taintStringRange(@Nullable String target, byte origin, int start, int length);

  /**
   * @see #taintObjectRange(IastContext, Object, byte, int, int)
   */
  void taintStringRange(
      @Nullable IastContext ctx, @Nullable String target, byte origin, int start, int length);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object)
   */
  void taintObjectIfTainted(@Nullable Object target, @Nullable Object input);

  /**
   * Taints the object only if the input value is tainted. If tainted, it will use the highest
   * priority source of the input to taint the object.
   */
  void taintObjectIfTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object input);

  /**
   * @see #taintStringIfTainted(IastContext, String, Object)
   */
  void taintStringIfTainted(@Nullable String target, @Nullable Object input);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object)
   */
  void taintStringIfTainted(
      @Nullable IastContext ctx, @Nullable String target, @Nullable Object input);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, boolean, int)
   */
  void taintObjectIfTainted(
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
  void taintObjectIfTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintStringIfTainted(IastContext, String, Object, boolean, int)
   */
  void taintStringIfTainted(
      @Nullable String target, @Nullable Object input, boolean keepRanges, int mark);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, boolean, int)
   */
  void taintStringIfTainted(
      @Nullable IastContext ctx,
      @Nullable String target,
      @Nullable Object input,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, boolean, int)
   */
  void taintObjectIfRangeTainted(
      @Nullable Object target,
      @Nullable Object input,
      int start,
      int length,
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
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      int start,
      int length,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintStringIfTainted(IastContext, String, Object, boolean, int)
   */
  void taintStringIfRangeTainted(
      @Nullable String target,
      @Nullable Object input,
      int start,
      int length,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, boolean, int)
   */
  void taintStringIfRangeTainted(
      @Nullable IastContext ctx,
      @Nullable String target,
      @Nullable Object input,
      int start,
      int length,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, byte)
   */
  void taintObjectIfTainted(@Nullable Object target, @Nullable Object input, byte origin);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and no name, if target is a char sequence it will be used as
   * value
   */
  void taintObjectIfTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object input, byte origin);

  /**
   * @see #taintStringIfTainted(IastContext, String, Object, byte)
   */
  void taintStringIfTainted(@Nullable String target, @Nullable Object input, byte origin);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, byte)
   */
  void taintStringIfTainted(
      @Nullable IastContext ctx, @Nullable String target, @Nullable Object input, byte origin);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, byte, CharSequence)
   */
  void taintObjectIfTainted(
      @Nullable Object target, @Nullable Object input, byte origin, @Nullable CharSequence name);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin and name, if target is a char sequence it will be used as
   * value
   */
  void taintObjectIfTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name);

  /**
   * @see #taintStringIfTainted(IastContext, String, Object, byte, CharSequence)
   */
  void taintStringIfTainted(
      @Nullable String target, @Nullable Object input, byte origin, @Nullable CharSequence name);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, byte, CharSequence)
   */
  void taintStringIfTainted(
      @Nullable IastContext ctx,
      @Nullable String target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, byte, CharSequence, Object)
   */
  void taintObjectIfTainted(
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * Taints the object only if the input value is tainted, the resulting value will be tainted using
   * a source with the specified origin, name and value.
   */
  void taintObjectIfTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * @see #taintStringIfTainted(IastContext, String, Object, byte, CharSequence, Object)
   */
  void taintStringIfTainted(
      @Nullable String target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * @see #taintObjectIfTainted(IastContext, Object, Object, byte, CharSequence, Object)
   */
  void taintStringIfTainted(
      @Nullable IastContext ctx,
      @Nullable String target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name,
      @Nullable Object value);

  /**
   * @see #taintObjectIfAnyTainted(IastContext, Object, Object[])
   */
  void taintObjectIfAnyTainted(@Nullable Object target, @Nullable Object[] inputs);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintObjectIfTainted(IastContext, Object, Object)}
   *
   * @see #taintObjectIfTainted(IastContext, Object, Object)
   */
  void taintObjectIfAnyTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object[] inputs);

  /**
   * @see #taintStringIfAnyTainted(IastContext, String, Object[])
   */
  void taintStringIfAnyTainted(@Nullable String target, @Nullable Object[] inputs);

  /**
   * @see #taintObjectIfAnyTainted(IastContext, Object, Object[])
   */
  void taintStringIfAnyTainted(
      @Nullable IastContext ctx, @Nullable String target, @Nullable Object[] inputs);

  /**
   * @see #taintObjectIfAnyTainted(IastContext, Object, Object[], boolean, int)
   */
  void taintObjectIfAnyTainted(
      @Nullable Object target, @Nullable Object[] inputs, boolean keepRanges, int mark);

  /**
   * Taints the object if any of the inputs is tainted. When a tainted input is found the logic is
   * the same as in {@link #taintObjectIfTainted(IastContext, Object, Object, boolean, int)}
   *
   * @see #taintObjectIfTainted(IastContext, Object, Object, boolean, int)
   */
  void taintObjectIfAnyTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object[] inputs,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintStringIfAnyTainted(IastContext, String, Object[], boolean, int)
   */
  void taintStringIfAnyTainted(
      @Nullable String target, @Nullable Object[] inputs, boolean keepRanges, int mark);

  /**
   * @see #taintObjectIfAnyTainted(IastContext, Object, Object[], boolean, int)
   */
  void taintStringIfAnyTainted(
      @Nullable IastContext ctx,
      @Nullable String target,
      @Nullable Object[] inputs,
      boolean keepRanges,
      int mark);

  /**
   * @see #taintObjectDeeply(IastContext, Object, byte, Predicate)
   */
  int taintObjectDeeply(@Nullable Object target, byte origin, Predicate<Class<?>> classFilter);

  /**
   * Visit the graph of the object and taints all the string properties found using a source with
   * the selected origin and no name.
   *
   * @param classFilter filter for types that should be included in the visiting process
   * @return number of tainted elements
   */
  int taintObjectDeeply(
      @Nullable IastContext ctx,
      @Nullable Object target,
      byte origin,
      Predicate<Class<?>> classFilter);

  /**
   * @see #isTainted(IastContext, Object)
   */
  boolean isTainted(@Nullable Object target);

  /** Checks if an arbitrary object is tainted */
  boolean isTainted(@Nullable IastContext ctx, @Nullable Object target);

  /**
   * @see #findSource(IastContext, Object)
   */
  @Nullable
  Source findSource(@Nullable Object target);

  /**
   * Returns the source with the highest priority if the object is tainted, {@code null} otherwise
   */
  @Nullable
  Source findSource(@Nullable IastContext ctx, @Nullable Object target);

  void markIfTainted(@Nullable Object target, int mark);
}
