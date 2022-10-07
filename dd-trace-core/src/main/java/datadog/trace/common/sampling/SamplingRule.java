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

  public static final class SpanSamplingRule<T extends CoreSpan<T>> extends SamplingRule<T> {
    private final Pattern servicePattern;
    private final Pattern operationPattern;

    private final SimpleRateLimiter rateLimiter;

    public SpanSamplingRule(
        final String serviceNameGlob,
        final String operationNameGlob,
        final RateSampler<T> sampler,
        final SimpleRateLimiter rateLimiter) {
      super(sampler);
      // TODO optimize if no globs such as * or ? are in use then implement exact match
      this.servicePattern = GlobPattern.globToRegexPattern(serviceNameGlob);
      this.operationPattern = GlobPattern.globToRegexPattern(operationNameGlob);
      this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean matches(T span) {
      return (servicePattern == null || servicePattern.matcher(span.getServiceName()).matches())
          && (operationPattern == null
              || operationPattern.matcher(span.getOperationName()).matches());
    }

    @Override
    public boolean sample(T span) {
      return super.sample(span) && (rateLimiter == null || rateLimiter.tryAcquire());
    }

    public void apply(T span) {
      // TODO set necessary
      //      span.setAllTags()
    }
  }
}
