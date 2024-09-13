package datadog.trace.api.civisibility.coverage;

import java.util.function.Supplier;
import javax.annotation.Nullable;

public abstract class CoveragePercentageBridge {

  private static volatile Supplier<byte[]> JACOCO_COVERAGE_DATA_SUPPLIER;

  public static void registerCoverageDataSupplier(Supplier<byte[]> jacocoCoverageDataSupplier) {
    JACOCO_COVERAGE_DATA_SUPPLIER = jacocoCoverageDataSupplier;
  }

  @Nullable
  public static byte[] getJacocoCoverageData() {
    return JACOCO_COVERAGE_DATA_SUPPLIER != null ? JACOCO_COVERAGE_DATA_SUPPLIER.get() : null;
  }
}
