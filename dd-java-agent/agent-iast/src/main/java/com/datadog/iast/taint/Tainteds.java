package com.datadog.iast.taint;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

/** Utilitiles to work with {@link TaintedObject} */
public final class Tainteds {

  private Tainteds() {}

  @Contract("null -> false")
  public static boolean canBeTainted(@Nullable final CharSequence s) {
    return s != null && s.length() > 0;
  }

  @Contract("null -> false")
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

  @Contract("null -> false")
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

  @Nullable
  public static TaintedObject getTainted(
      @Nonnull final TaintedObjects to, @Nullable final Object value) {
    return value == null ? null : to.get(value);
  }
}
