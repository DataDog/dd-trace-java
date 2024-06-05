package datadog.trace.bootstrap.instrumentation.jfr.backpressure;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper;

public final class BackpressureProfiling {

  private static final class Holder {
    static final BackpressureProfiling INSTANCE = new BackpressureProfiling(Config.get());
  }

  public static BackpressureProfiling getInstance() {
    return Holder.INSTANCE;
  }

  private final BackpressureSampler sampler;

  private BackpressureProfiling(final Config config) {
    this(new BackpressureSampler(config));
  }

  BackpressureProfiling(BackpressureSampler sampler) {
    this.sampler = sampler;
  }

  public void start() {
    sampler.start();
  }

  public void process(Class<?> backpressureMechanism, Object task) {
    if (sampler.sample()) {
      new BackpressureSampleEvent(backpressureMechanism, TaskWrapper.getUnwrappedType(task))
          .commit();
    }
  }
}
