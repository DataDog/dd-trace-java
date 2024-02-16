package com.datadog.iast.taint;

import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

/** Utilities to work with {@link TaintedObject} */
public final class Tainteds {

  private Tainteds() {}

  @Contract("null -> false")
  public static boolean canBeTainted(@Nullable final CharSequence s) {
    return s != null && s.length() > 0;
  }

  @Contract("null -> false")
  public static boolean canBeTainted(@Nullable final String s) {
    return s != null && !s.isEmpty();
  }

  @SuppressWarnings("RedundantLengthCheck")
  @Contract("null -> false")
  public static boolean canBeTainted(@Nullable final String[] e) {
    if (e == null || e.length == 0) {
      return false;
    }
    for (final String item : e) {
      if (canBeTainted(item)) {
        return true;
      }
    }
    return false;
  }

  @Contract("null -> false")
  public static boolean canBeTainted(@Nullable final Collection<String> e) {
    if (e == null || e.isEmpty()) {
      return false;
    }
    if (e instanceof ArrayList) {
      // indexed optimization for ArrayList to prevent the iterator allocation
      final ArrayList<String> list = (ArrayList<String>) e;
      for (int i = list.size() - 1; i >= 0; i--) {
        if (canBeTainted(list.get(i))) {
          return true;
        }
      }
    } else {
      for (final String item : e) {
        if (canBeTainted(item)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  public static TaintedObject getTainted(
      @Nonnull final TaintedObjects to, @Nullable final Object value) {
    return value == null ? null : to.get(value);
  }
}
