package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.GlobPattern;
import datadog.trace.core.util.SimpleRateLimiter;
import java.util.regex.Pattern;

public abstract class SamplingRule {
  private final RateSampler sampler;

  public SamplingRule(final RateSampler sampler) {
    this.sampler = sampler;
  }

  public abstract <T extends CoreSpan<T>> boolean matches(T span);

  public <T extends CoreSpan<T>> boolean sample(final T span) {
    return sampler.sample(span);
  }

  public RateSampler getSampler() {
    return sampler;
  }

  public static class AlwaysMatchesSamplingRule extends SamplingRule {

    public AlwaysMatchesSamplingRule(final RateSampler sampler) {
      super(sampler);
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(final T span) {
      return true;
    }
  }

  public abstract static class PatternMatchSamplingRule extends SamplingRule {
    private final Pattern pattern;

    public PatternMatchSamplingRule(final String regex, final RateSampler sampler) {
      super(sampler);
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(final T span) {
      final CharSequence relevantString = getRelevantString(span);
      return relevantString != null && pattern.matcher(relevantString).matches();
    }

    protected abstract <T extends CoreSpan<T>> CharSequence getRelevantString(T span);
  }

  public static class ServiceSamplingRule extends PatternMatchSamplingRule {
    public ServiceSamplingRule(final String regex, final RateSampler sampler) {
      super(regex, sampler);
    }

    @Override
    protected <T extends CoreSpan<T>> String getRelevantString(final T span) {
      return span.getServiceName();
    }
  }

  public static class OperationSamplingRule extends PatternMatchSamplingRule {
    public OperationSamplingRule(final String regex, final RateSampler sampler) {
      super(regex, sampler);
    }

    @Override
    protected <T extends CoreSpan<T>> CharSequence getRelevantString(final T span) {
      return span.getOperationName();
    }
  }

  public static final class TraceSamplingRule extends SamplingRule {
    private final String serviceName;
    private final String operationName;

    public TraceSamplingRule(
        final String exactServiceName, final String exactOperationName, final RateSampler sampler) {
      super(sampler);
      this.serviceName = exactServiceName;
      this.operationName = exactOperationName;
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(T span) {
      return (serviceName == null || serviceName.equals(span.getServiceName()))
          && (operationName == null || operationName.contentEquals(span.getOperationName()));
    }
  }

  public static final class SpanSamplingRule extends SamplingRule {
    private final String serviceExactName;
    private final Pattern servicePattern;
    private final String operationExactName;
    private final Pattern operationPattern;

    private final SimpleRateLimiter rateLimiter;

    public SpanSamplingRule(
        final String serviceName,
        final String operationName,
        final RateSampler sampler,
        final SimpleRateLimiter rateLimiter) {
      super(sampler);

      if (serviceName == null || "*".equals(serviceName)) {
        this.serviceExactName = null;
        this.servicePattern = null;
      } else if (isExactMatcher(serviceName)) {
        this.serviceExactName = serviceName;
        this.servicePattern = null;
      } else {
        this.serviceExactName = null;
        this.servicePattern = GlobPattern.globToRegexPattern(serviceName);
      }

      if (operationName == null || "*".equals(operationName)) {
        this.operationExactName = null;
        this.operationPattern = null;
      } else if (isExactMatcher(operationName)) {
        this.operationExactName = operationName;
        this.operationPattern = null;
      } else {
        this.operationExactName = null;
        this.operationPattern = GlobPattern.globToRegexPattern(operationName);
      }

      this.rateLimiter = rateLimiter;
    }

    private boolean isExactMatcher(String serviceNameGlob) {
      return !serviceNameGlob.contains("*") && !serviceNameGlob.contains("?");
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(T span) {
      return (serviceExactName == null || serviceExactName.equals(span.getServiceName()))
          && (servicePattern == null || servicePattern.matcher(span.getServiceName()).matches())
          && (operationExactName == null
              || operationExactName.contentEquals(span.getOperationName()))
          && (operationPattern == null
              || operationPattern.matcher(span.getOperationName()).matches());
    }

    @Override
    public <T extends CoreSpan<T>> boolean sample(T span) {
      return super.sample(span) && (rateLimiter == null || rateLimiter.tryAcquire());
    }

    public SimpleRateLimiter getRateLimiter() {
      return rateLimiter;
    }
  }
}
