package com.datadog.iast.propagation;

import static com.datadog.iast.model.SourceImpl.PROPAGATION_PLACEHOLDER;
import static com.datadog.iast.taint.Ranges.highestPriorityRange;
import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.RangeImpl;
import com.datadog.iast.model.SourceImpl;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.util.ObjectVisitor;
import datadog.trace.api.Config;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.Source;
import datadog.trace.api.iast.taint.TaintedObject;
import datadog.trace.api.iast.taint.TaintedObjects;
import datadog.trace.api.iast.util.Ranged;
import java.lang.ref.WeakReference;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("DuplicatedCode")
public class PropagationModuleImpl implements PropagationModule {

  /** Prevent copy of values bigger than this threshold */
  private static final int MAX_VALUE_LENGTH = Config.get().getIastTruncationMaxValueLength();

  @Override
  public void taintObject(@Nullable final TaintedObjects to, @Nullable Object target, byte origin) {
    taintObject(to, target, origin, null);
  }

  @Override
  public void taintObject(
      @Nullable final TaintedObjects to,
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name) {
    taintObject(to, target, origin, name, target);
  }

  @Override
  public void taintObjectRange(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      final byte origin,
      final int start,
      final int length) {
    if (target == null || length == 0) {
      return;
    }
    final Range range =
        new RangeImpl(start, length, newSource(target, origin, null, target), NOT_MARKED);
    internalTaint(to, target, new Range[] {range}, NOT_MARKED);
  }

  @Override
  public void taintObject(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (target == null) {
      return;
    }
    internalTaint(to, target, newSource(target, origin, name, value), NOT_MARKED);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final TaintedObjects to, @Nullable Object target, @Nullable Object input) {
    taintObjectIfTainted(to, target, input, false, NOT_MARKED);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      @Nullable final Object input,
      boolean keepRanges,
      int mark) {
    if (target == null || input == null) {
      return;
    }
    if (keepRanges) {
      internalTaint(to, target, getRanges(to, input), mark);
    } else {
      internalTaint(to, target, highestPrioritySource(to, input), mark);
    }
  }

  @Override
  public void taintObjectIfRangeTainted(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      @Nullable final Object input,
      final int start,
      final int length,
      boolean keepRanges,
      int mark) {
    if (target == null || input == null || length == 0) {
      return;
    }
    final Range[] ranges = getRanges(to, input);
    if (ranges == null || ranges.length == 0) {
      return;
    }
    final Range[] intersection = Ranges.intersection(Ranged.build(start, length), ranges);
    if (intersection == null || intersection.length == 0) {
      return;
    }
    if (keepRanges) {
      internalTaint(to, target, intersection, mark);
    } else {
      final Range range = highestPriorityRange(intersection);
      internalTaint(to, target, range.getSource(), mark);
    }
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final TaintedObjects to,
      @Nullable Object target,
      @Nullable Object input,
      byte origin) {
    taintObjectIfTainted(to, target, input, origin, null, target);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final TaintedObjects to,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name) {
    taintObjectIfTainted(to, target, input, origin, name, target);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (target == null || input == null) {
      return;
    }
    if (isTainted(to, input)) {
      internalTaint(to, target, newSource(target, origin, name, value), NOT_MARKED);
    }
  }

  @Override
  public void taintObjectIfAnyTainted(
      @Nullable final TaintedObjects to, @Nullable Object target, @Nullable Object[] inputs) {
    taintObjectIfAnyTainted(to, target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintObjectIfAnyTainted(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (target == null || inputs == null || inputs.length == 0) {
      return;
    }
    if (keepRanges) {
      final Range[] ranges = getRangesInArray(to, inputs);
      if (ranges != null) {
        internalTaint(to, target, ranges, mark);
      }
    } else {
      final Source source = highestPrioritySourceInArray(to, inputs);
      if (source != null) {
        internalTaint(to, target, source, mark);
      }
    }
  }

  @Override
  public int taintObjectDeeply(
      @Nullable final TaintedObjects to,
      @Nullable final Object target,
      final byte origin,
      final Predicate<Class<?>> classFilter) {
    if (to == null || target == null) {
      return 0;
    }
    if (target instanceof CharSequence) {
      internalTaint(to, target, newSource(target, origin, null, target), NOT_MARKED);
      return 1;
    } else {
      final TaintingVisitor visitor = new TaintingVisitor(to, origin);
      ObjectVisitor.visit(target, visitor, classFilter);
      return visitor.getCount();
    }
  }

  @Nullable
  @Override
  public Source findSource(@Nullable final TaintedObjects to, @Nullable final Object target) {
    if (target == null) {
      return null;
    }
    return highestPrioritySource(to, target);
  }

  @Override
  public boolean isTainted(@Nullable final TaintedObjects to, @Nullable final Object target) {
    return target != null && findSource(to, target) != null;
  }

  /**
   * Ensures that the reference is not kept due to a strong reference via the name or value
   * properties
   */
  private static Source newSource(
      @Nonnull final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    final Object sourceValue = sourceReference(target, value, true);
    final Object sourceName = name == value ? sourceValue : sourceReference(target, name, false);
    return new SourceImpl(origin, sourceName, sourceValue);
  }

  /**
   * This method will prevent the code from creating a strong reference to what should remain weakly
   * reachable
   */
  @Nullable
  private static Object sourceReference(
      @Nonnull final Object tainted, @Nullable final Object target, final boolean value) {
    if (target instanceof String) {
      // weak reference if it's a value or matches the tainted value
      return value || tainted == target ? new WeakReference<>(target) : target;
    } else if (target instanceof CharSequence) {
      // char-sequences can mutate so we have to keep a snapshot
      CharSequence charSequence = (CharSequence) target;
      if (charSequence.length() > MAX_VALUE_LENGTH) {
        charSequence = charSequence.subSequence(0, MAX_VALUE_LENGTH);
      }
      return charSequence.toString();
    }
    // ignore non char-sequence instances (e.g. byte buffers)
    return value ? PROPAGATION_PLACEHOLDER : null;
  }

  @Nullable
  private static Range[] getRangesInArray(
      final @Nullable TaintedObjects to, final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Range[] ranges = getRanges(to, object);
      if (ranges != null) {
        return ranges;
      }
    }
    return null;
  }

  @Nullable
  private static Range[] getRanges(
      final @Nullable TaintedObjects to, final @Nonnull Object object) {
    if (object instanceof Taintable) {
      final Source source = highestPrioritySource(to, object);
      if (source == null) {
        return null;
      } else {
        return new Range[] {new RangeImpl(0, Integer.MAX_VALUE, source, NOT_MARKED)};
      }
    } else if (to != null) {
      final TaintedObject tainted = to.get(object);
      return tainted == null ? null : (Range[]) tainted.getRanges();
    } else {
      return null;
    }
  }

  @Nullable
  private static Source highestPrioritySourceInArray(
      final @Nullable TaintedObjects to, final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Source source = highestPrioritySource(to, object);
      if (source != null) {
        return source;
      }
    }
    return null;
  }

  @Nullable
  private static Source highestPrioritySource(
      final @Nullable TaintedObjects to, final @Nonnull Object object) {
    if (object instanceof Taintable) {
      return ((Taintable) object).$$DD$getSource();
    } else if (to != null) {
      final Range[] ranges = getRanges(to, object);
      return ranges != null && ranges.length > 0 ? highestPriorityRange(ranges).getSource() : null;
    } else {
      return null;
    }
  }

  private static void internalTaint(
      @Nullable final TaintedObjects to,
      @Nonnull final Object value,
      @Nullable Source source,
      int mark) {
    if (source == null) {
      return;
    }
    if (value instanceof Taintable) {
      ((Taintable) value).$$DD$setSource(source);
    } else if (to != null) {
      if (value instanceof CharSequence) {
        source = attachSourceValue(source, (CharSequence) value);
        to.taint(value, Ranges.forCharSequence((CharSequence) value, source, mark));
      } else {
        to.taint(value, Ranges.forObject(source, mark));
      }
    }
  }

  private static void internalTaint(
      @Nullable final TaintedObjects to,
      @Nonnull final Object value,
      @Nullable Range[] ranges,
      final int mark) {
    if (ranges == null || ranges.length == 0) {
      return;
    }
    if (value instanceof Taintable) {
      final Taintable taintable = (Taintable) value;
      if (!taintable.$DD$isTainted()) {
        taintable.$$DD$setSource(ranges[0].getSource());
      }
    } else if (to != null) {
      if (value instanceof CharSequence) {
        ranges = attachSourceValue(ranges, (CharSequence) value);
      }
      ranges = markRanges(ranges, mark);
      final TaintedObject tainted = to.get(value);
      if (tainted != null) {
        // append ranges
        final Range[] newRanges = Ranges.mergeRangesSorted((Range[]) tainted.getRanges(), ranges);
        tainted.setRanges(newRanges);
      } else {
        // taint new value
        to.taint(value, ranges);
      }
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
      result[i] = new RangeImpl(range.getStart(), range.getLength(), range.getSource(), newMark);
    }
    return result;
  }

  private static Source attachSourceValue(final Source source, final CharSequence value) {
    final Object newValue = sourceReference(value, value, true);
    return newValue == null ? source : source.attachValue(newValue);
  }

  private static Range[] attachSourceValue(
      @Nonnull final Range[] ranges, @Nonnull final CharSequence value) {
    // unbound sources can only occur when there's a single range in the array
    if (ranges.length != 1) {
      return ranges;
    }
    final Range range = ranges[0];
    final Source source = range.getSource();
    final Source newSource = attachSourceValue(source, value);
    return newSource == source
        ? ranges
        : Ranges.forCharSequence(value, newSource, range.getMarks());
  }

  private static class TaintingVisitor implements ObjectVisitor.Visitor {

    private final TaintedObjects taintedObjects;
    private final byte origin;
    private int count;

    private TaintingVisitor(@Nonnull final TaintedObjects taintedObjects, final byte origin) {
      this.taintedObjects = taintedObjects;
      this.origin = origin;
    }

    @Nonnull
    @Override
    public ObjectVisitor.State visit(@Nonnull final String path, @Nonnull final Object value) {
      if (value instanceof CharSequence) {
        final CharSequence charSequence = (CharSequence) value;
        final Source source = newSource(charSequence, origin, path, charSequence);
        count++;
        taintedObjects.taint(
            charSequence, Ranges.forCharSequence(charSequence, source, NOT_MARKED));
      }
      return CONTINUE;
    }

    public int getCount() {
      return count;
    }
  }
}
