package com.datadog.iast.propagation;

import static com.datadog.iast.model.Range.NOT_MARKED;
import static com.datadog.iast.taint.Ranges.highestPriorityRange;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class PropagationModuleImpl implements PropagationModule {

  @Override
  public void taintIfInputIsTainted(@Nullable final Object toTaint, @Nullable final Object input) {
    if (toTaint == null || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    final Source source = highestPriorityTaintedSource(taintedObjects, input);
    if (source != null) {
      taintObject(taintedObjects, toTaint, source);
    }
  }

  @Override
  public void taintIfInputIsTainted(@Nullable final String toTaint, @Nullable final Object input) {
    if (!canBeTainted(toTaint) || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    final Source source = highestPriorityTaintedSource(taintedObjects, input);
    if (source != null) {
      taintString(taintedObjects, toTaint, source);
    }
  }

  @Override
  public void taintIfInputIsTainted(
      final byte origin,
      @Nullable final String name,
      @Nullable final String toTaint,
      @Nullable final Object input) {
    if (!canBeTainted(toTaint) || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    if (isTainted(taintedObjects, input)) {
      taintString(taintedObjects, toTaint, new Source(origin, name, toTaint));
    }
  }

  @Override
  public void taintIfInputIsTainted(
      final byte origin,
      @Nullable final String name,
      @Nullable final Collection<String> toTaintCollection,
      @Nullable final Object input) {
    if (toTaintCollection == null || toTaintCollection.isEmpty() || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    if (isTainted(taintedObjects, input)) {
      for (final String toTaint : toTaintCollection) {
        if (canBeTainted(toTaint)) {
          taintString(taintedObjects, toTaint, new Source(origin, name, toTaint));
        }
      }
    }
  }

  @Override
  public void taintIfInputIsTainted(
      final byte origin,
      @Nullable final Collection<String> toTaintCollection,
      @Nullable final Object input) {
    if (toTaintCollection == null || toTaintCollection.isEmpty() || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    if (isTainted(taintedObjects, input)) {
      for (final String toTaint : toTaintCollection) {
        if (canBeTainted(toTaint)) {
          taintString(taintedObjects, toTaint, new Source(origin, toTaint, toTaint));
        }
      }
    }
  }

  @Override
  public void taintIfInputIsTainted(
      final byte origin,
      @Nullable final List<Map.Entry<String, String>> toTaintCollection,
      @Nullable final Object input) {
    if (toTaintCollection == null || toTaintCollection.isEmpty() || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    if (isTainted(taintedObjects, input)) {
      for (final Map.Entry<String, String> entry : toTaintCollection) {
        final String name = entry.getKey();
        if (canBeTainted(name)) {
          taintString(
              taintedObjects, name, new Source(SourceTypes.namedSource(origin), name, name));
        }
        final String toTaint = entry.getValue();
        if (canBeTainted(toTaint)) {
          taintString(taintedObjects, toTaint, new Source(origin, name, toTaint));
        }
      }
    }
  }

  @Override
  public void taintIfAnyInputIsTainted(
      @Nullable final Object toTaint, @Nullable final Object... inputs) {
    if (toTaint == null || inputs == null || inputs.length == 0) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    for (final Object input : inputs) {
      final Source source = highestPriorityTaintedSource(taintedObjects, input);
      if (source != null) {
        taintObject(taintedObjects, toTaint, source);
        return;
      }
    }
  }

  @Override
  public void taint(final byte source, @Nullable final String name, @Nullable final String value) {
    if (!canBeTainted(value)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(value, new Source(source, name, value));
  }

  @Override
  public void taint(
      @Nullable final Object ctx_,
      final byte source,
      @Nullable final String name,
      @Nullable final String value) {
    if (ctx_ == null || !canBeTainted(value)) {
      return;
    }
    final IastRequestContext ctx = (IastRequestContext) ctx_;
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(value, new Source(source, name, value));
  }

  @Override
  public void taintObjectIfInputIsTaintedKeepingRanges(
      @Nullable final Object toTaint, @Nullable Object input) {
    if (toTaint == null || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    final Range[] ranges = getTaintedRanges(taintedObjects, input);
    if (ranges != null && ranges.length > 0) {
      taintedObjects.taint(toTaint, ranges);
    }
  }

  @Override
  public void taintObject(final byte origin, @Nullable final Object toTaint) {
    if (toTaint == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(false);
    if (taintedObjects == null) {
      return;
    }
    final Source source = new Source(origin, null, null);
    taintObject(taintedObjects, toTaint, source);
  }

  @Override
  public void taintObjects(final byte origin, @Nullable final Object[] toTaintArray) {
    if (toTaintArray == null || toTaintArray.length == 0) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    final Source source = new Source(origin, null, null);
    for (final Object toTaint : toTaintArray) {
      taintObject(taintedObjects, toTaint, source);
    }
  }

  @Override
  public boolean isTainted(@Nullable Object obj) {
    if (obj instanceof Taintable) {
      return ((Taintable) obj).$DD$isTainted();
    }

    if (obj == null) {
      return false;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    return taintedObjects.get(obj) != null;
  }

  @Override
  public void taintObjects(
      final byte origin, @Nullable final Collection<Object> toTaintCollection) {
    if (toTaintCollection == null || toTaintCollection.isEmpty()) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    final Source source = new Source(origin, null, null);
    for (final Object toTaint : toTaintCollection) {
      taintObject(taintedObjects, toTaint, source);
    }
  }

  @Override
  public void taint(
      byte origin, @Nullable String name, @Nullable String value, @Nullable Taintable t) {
    if (t == null) {
      return;
    }
    t.$$DD$setSource(new Source(origin, name, value));
  }

  @Override
  public Taintable.Source firstTaintedSource(@Nullable final Object input) {
    if (input == null) {
      return null;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    return highestPriorityTaintedSource(taintedObjects, input);
  }

  @Override
  public void taintIfInputIsTaintedWithMarks(
      @Nullable final String toTaint, @Nullable final Object input, final int mark) {
    if (!canBeTainted(toTaint) || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = TaintedObjects.activeTaintedObjects(true);
    final Range[] ranges = getTaintedRanges(taintedObjects, input);
    if (ranges != null && ranges.length > 0) {
      Range priorityRange = highestPriorityRange(ranges);
      taintedObjects.taintInputString(
          toTaint, priorityRange.getSource(), priorityRange.getMarks() | mark);
    }
  }

  private static void taintString(
      final TaintedObjects taintedObjects, final String toTaint, final Source source) {
    taintedObjects.taintInputString(toTaint, source);
  }

  private static void taintObject(
      final TaintedObjects taintedObjects, final Object toTaint, final Source source) {
    if (toTaint instanceof Taintable) {
      ((Taintable) toTaint).$$DD$setSource(source);
    } else {
      taintedObjects.taintInputObject(toTaint, source);
    }
  }

  private static boolean isTainted(final TaintedObjects taintedObjects, final Object object) {
    return highestPriorityTaintedSource(taintedObjects, object) != null;
  }

  private static Source highestPriorityTaintedSource(
      final TaintedObjects taintedObjects, final Object object) {
    if (object instanceof Taintable) {
      return (Source) ((Taintable) object).$$DD$getSource();
    } else {
      final TaintedObject tainted = taintedObjects.get(object);
      final Range[] ranges = tainted == null ? null : tainted.getRanges();
      return ranges != null && ranges.length > 0 ? highestPriorityRange(ranges).getSource() : null;
    }
  }

  private static Range[] getTaintedRanges(
      final TaintedObjects taintedObjects, final Object object) {
    if (object instanceof Taintable) {
      Source source = (Source) ((Taintable) object).$$DD$getSource();
      if (source == null) {
        return null;
      } else {
        return Ranges.forObject(source, NOT_MARKED);
      }
    } else {
      final TaintedObject tainted = taintedObjects.get(object);
      return tainted == null ? null : tainted.getRanges();
    }
  }
}
