package com.datadog.iast.taint;

import java.util.Collection;
import javax.annotation.Nullable;

/** Utilitiles to work with {@link TaintedObject} */
public final class Tainteds {

  private Tainteds() {}

  public static boolean canBeTainted(@Nullable final CharSequence s) {
    return s != null && s.length() > 0;
  }

  public static <E extends CharSequence> boolean canBeTainted(@Nullable final E[] e) {
    if (e == null || e.length == 0) {
      return false;
    }
    for (final E item : e) {
      if (canBeTainted(item)) {
        return true;
      }
    }
    return false;
  }

  public static <E extends CharSequence> boolean canBeTainted(@Nullable final Collection<E> e) {
    if (e == null || e.isEmpty()) {
      return false;
    }
    for (final E item : e) {
      if (canBeTainted(item)) {
        return true;
      }
    }
    return false;
  }

  public static TaintedObject getTainted(final TaintedObjects to, final Object value) {
    return value == null ? null : to.get(value);
  }
}
