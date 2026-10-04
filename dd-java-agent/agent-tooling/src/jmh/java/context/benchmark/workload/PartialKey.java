package context.benchmark.workload;

import context.benchmark.workload.StoreKeys.S0;
import context.benchmark.workload.StoreKeys.S1;
import context.benchmark.workload.StoreKeys.S2;
import context.benchmark.workload.StoreKeys.S3;
import context.benchmark.workload.StoreKeys.S4;
import context.benchmark.workload.StoreKeys.S5;
import context.benchmark.workload.StoreKeys.S6;
import context.benchmark.workload.StoreKeys.S7;

/**
 * Field-injected key that only has fields for stores 0-7, so stores 8-15 redirect to the weak map.
 */
public final class PartialKey implements WorkloadKey, S0, S1, S2, S3, S4, S5, S6, S7 {
  @Override
  public Object store(int storeIndex) {
    return null;
  }

  @Override
  public WorkloadKey newKey() {
    return new PartialKey();
  }
}
