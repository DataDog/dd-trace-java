package com.datadog.profiling.util;

import java.util.Objects;
import lombok.Generated;

/** Compute hash code which will never be 0 */
@Generated // trivial delegated implementation; ignore in jacoco
public final class NonZeroHashCode {
  public static int hash(Object... values) {
    int code = Objects.hash(values);
    return code == 0 ? 1 : code; // if the computed hash is 0 bump it up to 1
  }
}
