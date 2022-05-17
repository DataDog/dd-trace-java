package datadog.communication.http;

/**
 * Used to define simple retry policies based on exponential backoff mechanism. Typically used for
 * retrying HTTP calls in the DDIntakeApi class.
 */
public class RetryPolicy {

  private final int maxRetries;
  private final long delay;
  private final double delayFactor;

  public static RetryPolicyBuilder builder() {
    return new RetryPolicyBuilder();
  }

  private RetryPolicy(final RetryPolicyBuilder builder) {
    this.maxRetries = builder.maxRetries;
    this.delay = builder.delayMs;
    this.delayFactor = builder.delayFactor;
  }

  public boolean shouldRetry(int retry) {
    return retry < maxRetries;
  }

  public long backoff(int attempt) {
    return Double.valueOf(delay * (Math.pow(delayFactor, attempt))).longValue();
  }

  public static class RetryPolicyBuilder {
    private int maxRetries;
    private long delayMs;
    private double delayFactor;

    public RetryPolicyBuilder withMaxRetry(int maxRetries) {
      this.maxRetries = Math.max(maxRetries, 0);
      return this;
    }

    public RetryPolicyBuilder withBackoff(long delayMs) {
      return withBackoff(delayMs, 2.0);
    }

    public RetryPolicyBuilder withBackoff(long delayMs, double delayMultiplier) {
      this.delayMs = delayMs;
      this.delayFactor = delayMultiplier;
      return this;
    }

    public RetryPolicy build() {
      return new RetryPolicy(this);
    }
  }
}
