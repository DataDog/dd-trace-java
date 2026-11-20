package context.benchmark.workload;

import context.benchmark.workload.StoreKeys.S10;
import context.benchmark.workload.StoreKeys.S11;
import context.benchmark.workload.StoreKeys.S12;
import context.benchmark.workload.StoreKeys.S13;
import context.benchmark.workload.StoreKeys.S14;
import context.benchmark.workload.StoreKeys.S15;
import context.benchmark.workload.StoreKeys.S8;
import context.benchmark.workload.StoreKeys.S9;

/** Loaded before the agent so subclasses fall back to the weak map for stores 8-15. */
public abstract class PartialKeyBase implements WorkloadKey, S8, S9, S10, S11, S12, S13, S14, S15 {}
