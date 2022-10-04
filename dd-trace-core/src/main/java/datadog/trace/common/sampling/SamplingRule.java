package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.GlobPattern;
import datadog.trace.core.util.SimpleRateLimiter;
import java.util.regex.Pattern;

public abstract class SamplingRule<T extends CoreSpan<T>> {
  private final RateSampler<T> sampler;

  public SamplingRule(final RateSampler<T> sampler) {
    this.sampler = sampler;
  }

  public abstract boolean matches(T span);

  public boolean sample(final T span) {
    return sampler.sample(span);
  }

  public RateSampler<T> getSampler() {
    return sampler;
  }

  public static class AlwaysMatchesSamplingRule<T extends CoreSpan<T>> extends SamplingRule<T> {

    public AlwaysMatchesSamplingRule(final RateSampler<T> sampler) {
      super(sampler);
    }

    @Override
    public boolean matches(final T span) {
      return true;
    }
  }

  public abstract static class PatternMatchSamplingRule<T extends CoreSpan<T>>
      extends SamplingRule<T> {
    private final Pattern pattern;

    public PatternMatchSamplingRule(final String regex, final RateSampler<T> sampler) {
      super(sampler);
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean matches(final T span) {
      final CharSequence relevantString = getRelevantString(span);
      return relevantString != null && pattern.matcher(relevantString).matches();
    }

    protected abstract CharSequence getRelevantString(T span);
  }

  public static class ServiceSamplingRule<T extends CoreSpan<T>>
      extends PatternMatchSamplingRule<T> {
    public ServiceSamplingRule(final String regex, final RateSampler<T> sampler) {
      super(regex, sampler);
    }

    @Override
    protected String getRelevantString(final T span) {
      return span.getServiceName();
    }
  }

  public static class OperationSamplingRule<T extends CoreSpan<T>>
      extends PatternMatchSamplingRule<T> {
    public OperationSamplingRule(final String regex, final RateSampler<T> sampler) {
      super(regex, sampler);
    }

    @Override
    protected CharSequence getRelevantString(final T span) {
      return span.getOperationName();
    }
  }

  public static final class TraceSamplingRule<T extends CoreSpan<T>> extends SamplingRule<T> {
    private final String serviceName;
    private final String operationName;

    public TraceSamplingRule(
        final String exactServiceName,
        final String exactOperationName,
        final RateSampler<T> sampler) {
      super(sampler);
      this.serviceName = exactServiceName;
      this.operationName = exactOperationName;
    }

    @Override
    public boolean matches(T span) {
      return (serviceName == null || serviceName.equals(span.getServiceName()))
          && (operationName == null || operationName.contentEquals(span.getOperationName()));
    }
  }

  // TODO how to combine this with a rate-limiter?
  public static final class SpanSamplingRule<T extends CoreSpan<T>> extends SamplingRule<T> {
    private final Pattern servicePattern;
    private final Pattern operationPattern;

    private final SimpleRateLimiter rateLimiter;

    public SpanSamplingRule(
        final String serviceNameGlob,
        final String operationNameGlob,
        final RateSampler<T> sampler) {
      super(sampler);
      servicePattern = GlobPattern.globToRegexPattern(serviceNameGlob);
      operationPattern = GlobPattern.globToRegexPattern(operationNameGlob);
      rateLimiter = null; // TODO
    }

    @Override
    public boolean matches(T span) {
      // TODO do we run rateLimiter here or in the sample method? Before or after the match.
      return (servicePattern == null || servicePattern.matcher(span.getServiceName()).matches())
          && (operationPattern == null
              || operationPattern.matcher(span.getOperationName()).matches());
    }
  }
}
