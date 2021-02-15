package datadog.trace.api.sampling;

public class PrioritySampling {
  /**
   * Implementation detail of the client. will not be sent to the agent or propagated.
   *
   * <p>Internal value used when the priority sampling flag has not been set on the span context.
   */
  public static final byte UNSET = (byte) 0x80;
  /** The sampler has decided to drop the trace. */
  public static final byte SAMPLER_DROP = 0;
  /** The sampler has decided to keep the trace. */
  public static final byte SAMPLER_KEEP = 1;
  /** The user has decided to drop the trace. */
  public static final byte USER_DROP = -1;
  /** The user has decided to keep the trace. */
  public static final byte USER_KEEP = 2;
  /** This trace contains a rare span not seen within the metrics interval */
  public static final byte METRICS_KEEP = 3;

  private PrioritySampling() {}
}
