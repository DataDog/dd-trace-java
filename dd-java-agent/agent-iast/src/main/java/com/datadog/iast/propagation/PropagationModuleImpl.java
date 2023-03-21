package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nullable;

public class PropagationModuleImpl implements PropagationModule {

  @Override
  public void taintIfInputIsTainted(@Nullable final Object toTaint, @Nullable final Object input) {
    if (toTaint == null || input == null) {
      return;
    }
    if (toTaint instanceof Taintable && input instanceof Taintable) {
      final Taintable.Source source = ((Taintable) input).$$DD$getSource();
      if (source != null) {
        ((Taintable) toTaint).$$DD$setSource(source);
      }
    } else {
      final TaintedObjects taintedObjects = getTaintedObjects();
      if (taintedObjects == null) {
        return;
      }
      final Source source = getFirstSource(taintedObjects, input);
      if (source != null) {
        if (toTaint instanceof Taintable) {
          ((Taintable) toTaint).$$DD$setSource(source);
        } else {
          taintedObjects.taintInputObject(toTaint, source);
        }
      }
    }
  }

  @Override
  public void taintIfInputIsTainted(@Nullable final String toTaint, @Nullable final Object input) {
    if (!canBeTainted(toTaint) || input == null) {
      return;
    }
    final TaintedObjects taintedObjects = getTaintedObjects();
    if (taintedObjects == null) {
      return;
    }
    final Source source = getFirstSource(taintedObjects, input);
    if (source != null) {
      taintedObjects.taintInputString(toTaint, source);
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
    final TaintedObjects taintedObjects = getTaintedObjects();
    if (taintedObjects == null) {
      return;
    }
    final Source source = getFirstSource(taintedObjects, input);
    if (source != null) {
      taintedObjects.taintInputString(toTaint, new Source(origin, name, toTaint));
    }
  }

  @Override
  public void taint(final byte origin, @Nullable final Object... toTaintArray) {
    if (toTaintArray == null || toTaintArray.length == 0) {
      return;
    }
    boolean contextQueried = false;
    TaintedObjects taintedObjects = null;
    final Source source = new Source(origin, null, null);
    for (final Object toTaint : toTaintArray) {
      if (toTaint instanceof Taintable) {
        ((Taintable) toTaint).$$DD$setSource(source);
      } else {
        if (!contextQueried) {
          taintedObjects = getTaintedObjects();
          contextQueried = true;
        }
        if (taintedObjects != null) {
          taintedObjects.taintInputObject(toTaint, source);
        }
      }
    }
  }

  private static Source getFirstSource(final TaintedObjects taintedObjects, final Object object) {
    if (object instanceof Taintable) {
      return (Source) ((Taintable) object).$$DD$getSource();
    } else {
      final TaintedObject tainted = taintedObjects.get(object);
      final Range[] ranges = tainted == null ? null : tainted.getRanges();
      return ranges != null && ranges.length > 0 ? ranges[0].getSource() : null;
    }
  }

  private static TaintedObjects getTaintedObjects() {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return null;
    }
    return ctx.getTaintedObjects();
  }
}
