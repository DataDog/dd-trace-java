package datadog.trace.common.sampling;

public interface RateSampler extends Sampler {
  double getSampleRate();

  /**
   * Returns the pre-formatted Knuth sampling rate as a string. This is computed once at
   * construction time to avoid thread-safety issues and performance overhead of formatting on each
   * sampling operation.
   */
  String getKnuthSampleRate();
}
