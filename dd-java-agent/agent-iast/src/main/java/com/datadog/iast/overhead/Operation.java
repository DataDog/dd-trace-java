package com.datadog.iast.overhead;

public interface Operation {
  boolean hasQuota(final OverheadContext context);

  boolean consumeQuota(final OverheadContext context);
}
