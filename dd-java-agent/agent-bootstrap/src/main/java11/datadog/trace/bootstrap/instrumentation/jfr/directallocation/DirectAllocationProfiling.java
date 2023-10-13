package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import datadog.trace.api.Config;

public class DirectAllocationProfiling {

  private static class Holder {
    static final DirectAllocationProfiling INSTANCE = new DirectAllocationProfiling(Config.get());
  }

  /**
   * Get a pre-configured shared instance.
   *
   * @return the shared instance
   */
  public static DirectAllocationProfiling getInstance() {
    return DirectAllocationProfiling.Holder.INSTANCE;
  }

  private final DirectAllocationSampler sampler;
  private final AllocatorHistogram histogram;
  private final StackWalker stackWalker;

  DirectAllocationProfiling(DirectAllocationSampler sampler, AllocatorHistogram histogram) {
    this.sampler = sampler;
    this.histogram = histogram;
    this.stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  }

  private DirectAllocationProfiling(Config config) {
    this(new DirectAllocationSampler(config), new AllocatorHistogram());
  }

  public StackWalker getStackWalker() {
    return stackWalker;
  }

  public DirectAllocationSampleEvent sample(
      DirectAllocationSource source, Class<?> caller, long bytes) {
    boolean firstHit = histogram.record(caller, source, bytes);
    if (sampler.sample() || firstHit) {
      return new DirectAllocationSampleEvent(caller.getName(), source.name(), bytes);
    }
    return null;
  }
}
