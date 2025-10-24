package com.datadog.featureflag.ufc.v1;

import java.util.List;
import java.util.Map;

public class Flag {
  public final String key;
  public final boolean enabled;
  public final ValueType variationType;
  public final Map<String, Variant> variations;
  public final List<Allocation> allocations;

  public Flag(
      String key,
      boolean enabled,
      ValueType variationType,
      Map<String, Variant> variations,
      List<Allocation> allocations) {
    this.key = key;
    this.enabled = enabled;
    this.variationType = variationType;
    this.variations = variations;
    this.allocations = allocations;
  }
}
