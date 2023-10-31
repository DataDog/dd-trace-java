package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Ranges.highestPriorityRange;
import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.taint.Tainteds;
import com.datadog.iast.util.ObjectVisitor;
import datadog.trace.api.Config;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

public class PropagationModuleImpl implements PropagationModule {

  /** Prevent copy of values bigger than this threshold */
  private static final int MAX_VALUE_LENGTH = Config.get().getIastTruncationMaxValueLength();

  private final TaintedObjects taintedObjects;

  public PropagationModuleImpl(@Nonnull final Dependencies dependencies) {
    this.taintedObjects = dependencies.getTaintedObjects();
  }

  @Override
  public void taint(@Nullable final Object target, final byte origin) {
    taint(target, origin, null);
  }

  @Override
  public void taint(
      @Nullable final Object target, final byte origin, @Nullable final CharSequence name) {
    taint(target, origin, name, sourceValue(target));
  }

  @Override
  public void taint(
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final CharSequence value) {
    if (!canBeTainted(target)) {
      return;
    }
    internalTaint(target, newSource(target, origin, name, value), NOT_MARKED);
  }

  @Override
  public void taintIfTainted(@Nullable final Object target, @Nullable final Object input) {
    taintIfTainted(target, input, false, NOT_MARKED);
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target, @Nullable final Object input, boolean keepRanges, int mark) {
    if (!canBeTainted(target) || !canBeTainted(input)) {
      return;
    }
    if (keepRanges) {
      final Range[] ranges = getRanges(input);
      if (ranges != null) {
        internalTaint(target, ranges, mark);
      }
    } else {
      final Source source = highestPrioritySource(input);
      if (source != null) {
        internalTaint(target, source, mark);
      }
    }
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target, @Nullable final Object input, final byte origin) {
    taintIfTainted(target, input, origin, null);
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name) {
    taintIfTainted(target, input, origin, name, sourceValue(target));
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final CharSequence value) {
    if (!canBeTainted(target) || !canBeTainted(input)) {
      return;
    }
    if (isTainted(input)) {
      internalTaint(target, newSource(target, origin, name, value), NOT_MARKED);
    }
  }

  @Override
  public void taintIfAnyTainted(@Nullable final Object target, @Nullable final Object[] inputs) {
    taintIfAnyTainted(target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintIfAnyTainted(
      @Nullable final Object target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (!canBeTainted(target) || !canBeTainted(inputs)) {
      return;
    }
    if (keepRanges) {
      final Range[] ranges = getRangesInArray(inputs);
      if (ranges != null) {
        internalTaint(target, ranges, mark);
      }
    } else {
      final Source source = highestPrioritySourceInArray(inputs);
      if (source != null) {
        internalTaint(target, source, mark);
      }
    }
  }

  @Override
  public void taintDeeply(
      @Nullable final Object target, final byte origin, final Predicate<Class<?>> classFilter) {
    if (!canBeTainted(target)) {
      return;
    }
    if (target instanceof CharSequence) {
      internalTaint(target, newSource(target, origin, null, sourceValue(target)), NOT_MARKED);
    } else {
      ObjectVisitor.visit(target, new TaintingVisitor(taintedObjects, origin), classFilter);
    }
  }

  @Nullable
  @Override
  public Taintable.Source findSource(@Nullable final Object target) {
    if (target == null) {
      return null;
    }
    return highestPrioritySource(target);
  }

  @Override
  public boolean isTainted(@Nullable final Object target) {
    return target != null && findSource(target) != null;
  }

  /** Ensures that the reference is not kept strongly via the name or value properties */
  private static Source newSource(
      @Nonnull final Object reference,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final CharSequence value) {
    return new Source(
        origin,
        reference == name ? sourceValue(name) : name,
        reference == value ? sourceValue(value) : value);
  }

  /**
   * This method will prevent the code from creating a strong reference to what should remain weak
   */
  @Nullable
  private static CharSequence sourceValue(@Nullable final Object target) {
    if (target instanceof String) {
      final String string = (String) target;
      if (MAX_VALUE_LENGTH > string.length()) {
        return String.copyValueOf(string.toCharArray());
      } else {
        final char[] chars = new char[MAX_VALUE_LENGTH];
        string.getChars(0, MAX_VALUE_LENGTH, chars, 0);
        return String.copyValueOf(chars);
      }
    } else if (target instanceof CharSequence) {
      final CharSequence charSequence = (CharSequence) target;
      if (MAX_VALUE_LENGTH > charSequence.length()) {
        return charSequence.toString();
      } else {
        final CharSequence subSequence = charSequence.subSequence(0, MAX_VALUE_LENGTH);
        return subSequence.toString();
      }
    }
    return null;
  }

  @Contract("null -> false")
  private static boolean canBeTainted(@Nullable final Object target) {
    if (target == null) {
      return false;
    }
    if (target instanceof CharSequence) {
      return Tainteds.canBeTainted((CharSequence) target);
    }
    return true;
  }

  @Contract("null -> false")
  private static boolean canBeTainted(@Nullable final Object[] target) {
    if (target == null || target.length == 0) {
      return false;
    }
    return true;
  }

  @Nullable
  private Range[] getRangesInArray(final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Range[] ranges = getRanges(object);
      if (ranges != null) {
        return ranges;
      }
    }
    return null;
  }

  @Nullable
  private Range[] getRanges(final @Nonnull Object object) {
    if (object instanceof Taintable) {
      final Source source = highestPrioritySource(object);
      if (source == null) {
        return null;
      } else {
        return new Range[] {new Range(0, Integer.MAX_VALUE, source, NOT_MARKED)};
      }
    }
    final TaintedObject tainted = taintedObjects.get(object);
    return tainted == null ? null : tainted.getRanges();
  }

  @Nullable
  private Source highestPrioritySourceInArray(final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Source source = highestPrioritySource(object);
      if (source != null) {
        return source;
      }
    }
    return null;
  }

  @Nullable
  private Source highestPrioritySource(final @Nonnull Object object) {
    if (object instanceof Taintable) {
      return (Source) ((Taintable) object).$$DD$getSource();
    } else {
      final Range[] ranges = getRanges(object);
      return ranges != null && ranges.length > 0 ? highestPriorityRange(ranges).getSource() : null;
    }
  }

  private void internalTaint(@Nonnull final Object value, @Nonnull final Source source, int mark) {
    if (value instanceof Taintable) {
      ((Taintable) value).$$DD$setSource(source);
    } else {
      if (value instanceof CharSequence) {
        taintedObjects.taint(value, Ranges.forCharSequence((CharSequence) value, source, mark));
      } else {
        taintedObjects.taint(value, Ranges.forObject(source, mark));
      }
    }
  }

  private void internalTaint(
      @Nonnull final Object value, @Nullable final Range[] ranges, final int mark) {
    if (ranges == null || ranges.length == 0) {
      return;
    }
    if (value instanceof Taintable) {
      ((Taintable) value).$$DD$setSource(ranges[0].getSource());
    } else {
      final Range[] markedRanges = markRanges(ranges, mark);
      taintedObjects.taint(value, markedRanges);
    }
  }

  @Nonnull
  private static Range[] markRanges(@Nonnull final Range[] ranges, final int mark) {
    if (mark == NOT_MARKED) {
      return ranges;
    }
    final Range[] result = new Range[ranges.length];
    for (int i = 0; i < ranges.length; i++) {
      final Range range = ranges[i];
      final int newMark = range.getMarks() | mark;
      result[i] = new Range(range.getStart(), range.getLength(), range.getSource(), newMark);
    }
    return result;
  }

  private static class TaintingVisitor implements ObjectVisitor.Visitor {

    private final TaintedObjects taintedObjects;
    private final byte origin;

    private TaintingVisitor(@Nonnull final TaintedObjects taintedObjects, final byte origin) {
      this.taintedObjects = taintedObjects;
      this.origin = origin;
    }

    @Nonnull
    @Override
    public ObjectVisitor.State visit(@Nonnull final String path, @Nonnull final Object value) {
      if (value instanceof CharSequence) {
        final CharSequence charSequence = (CharSequence) value;
        if (canBeTainted(charSequence)) {
          final Source source = newSource(value, origin, path, charSequence);
          taintedObjects.taint(
              charSequence, Ranges.forCharSequence(charSequence, source, NOT_MARKED));
        }
      }
      return CONTINUE;
    }
  }
}
