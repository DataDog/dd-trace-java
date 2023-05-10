package datadog.trace.api.sampling;

import static datadog.trace.api.sampling.PrioritySampling.*;

public class SamplingMechanism {
  /** Not encouraged to use */
  public static final byte UNKNOWN = -1;
  /** Used before the tracer receives any rates from agent and there are no rules configured */
  public static final byte DEFAULT = 0;
  /** The sampling rate received in the agent's http response */
  public static final byte AGENT_RATE = 1;
  /** Auto; reserved for future use */
  public static final byte REMOTE_AUTO_RATE = 2;
  /** Sampling rule or sampling rate based on tracer config */
  public static final byte RULE = 3;
  /** User directly sets sampling priority via code using span.SetTag(ManualKeep) or similar API */
  public static final byte MANUAL = 4;
  /** AppSec */
  public static final byte APPSEC = 5;
  /** User-defined target; reserved for future use */
  public static final byte REMOTE_USER_RATE = 6;
  /** Span Sampling Rate (single span sampled on account of a span sampling rule) */
  public static final byte SPAN_SAMPLING_RATE = 8;
  /** Force override sampling decision from external source, like W3C traceparent. */
  public static final byte EXTERNAL_OVERRIDE = Byte.MIN_VALUE;

  public static boolean validateWithSamplingPriority(int mechanism, int priority) {
    switch (mechanism) {
      case UNKNOWN:
        return true;

      case DEFAULT:
      case AGENT_RATE:
      case REMOTE_AUTO_RATE:
        return priority == SAMPLER_DROP || priority == SAMPLER_KEEP;

      case RULE:
      case MANUAL:
      case REMOTE_USER_RATE:
        return priority == USER_DROP || priority == USER_KEEP;

      case APPSEC:
        return priority == PrioritySampling.USER_KEEP;

      case EXTERNAL_OVERRIDE:
        return false;
    }
    return true;
  }

  private SamplingMechanism() {}
}
