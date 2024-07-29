package datadog.trace.api.civisibility.coverage;

import javax.annotation.Nullable;

public interface CoverageDataSupplier {
  @Nullable
  byte[] get();
}
