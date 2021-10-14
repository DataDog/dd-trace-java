package datadog.trace.api.sampling;

public interface Sampler {

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  boolean sample();

  /**
   * Force the sampling decision to keep this item
   *
   * @return always {@literal true}
   */
  boolean keep();

  /**
   * Force the sampling decision to drop this item
   *
   * @return always {@literal false}
   */
  boolean drop();
}
