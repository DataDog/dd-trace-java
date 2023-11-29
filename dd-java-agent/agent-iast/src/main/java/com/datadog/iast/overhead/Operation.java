package com.datadog.iast.overhead;

import javax.annotation.Nullable;

public interface Operation {
  boolean hasQuota(@Nullable final OverheadContext context);

  boolean consumeQuota(@Nullable final OverheadContext context);
}
