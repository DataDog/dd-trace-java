package context.benchmark.workload;

import context.benchmark.workload.StoreKeys.S0;
import context.benchmark.workload.StoreKeys.S1;
import context.benchmark.workload.StoreKeys.S10;
import context.benchmark.workload.StoreKeys.S11;
import context.benchmark.workload.StoreKeys.S12;
import context.benchmark.workload.StoreKeys.S13;
import context.benchmark.workload.StoreKeys.S14;
import context.benchmark.workload.StoreKeys.S15;
import context.benchmark.workload.StoreKeys.S2;
import context.benchmark.workload.StoreKeys.S3;
import context.benchmark.workload.StoreKeys.S4;
import context.benchmark.workload.StoreKeys.S5;
import context.benchmark.workload.StoreKeys.S6;
import context.benchmark.workload.StoreKeys.S7;
import context.benchmark.workload.StoreKeys.S8;
import context.benchmark.workload.StoreKeys.S9;

/** Key loaded before the agent is installed, so every store falls back to the weak map. */
public final class UninjectedKey
    implements WorkloadKey, S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14, S15 {
  @Override
  public Object store(int storeIndex) {
    return null;
  }

  @Override
  public WorkloadKey newKey() {
    return new UninjectedKey();
  }
}
