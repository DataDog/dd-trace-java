package datadog.trace.common.sampling;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.Matcher;
import datadog.trace.core.util.Matchers;
import datadog.trace.core.util.SimpleRateLimiter;
import datadog.trace.core.util.TagsMatcher;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class RateSamplingRule {
  private final RateSampler sampler;
  private final byte mechanism;

  public RateSamplingRule(final RateSampler sampler, byte mechanism) {
    this.sampler = sampler;
    this.mechanism = mechanism;
  }

  @Deprecated
  public RateSamplingRule(final RateSampler sampler) {
    this(sampler, SamplingMechanism.LOCAL_USER_RULE);
  }

  public abstract <T extends CoreSpan<T>> boolean matches(T span);

  public <T extends CoreSpan<T>> boolean sample(final T span) {
    return sampler.sample(span);
  }

  public RateSampler getSampler() {
    return sampler;
  }

  public final byte getMechanism() {
    return mechanism;
  }

  public static class AlwaysMatchesSamplingRule extends RateSamplingRule {
    public AlwaysMatchesSamplingRule(final RateSampler sampler, byte samplingMechanism) {
      super(sampler, samplingMechanism);
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(final T span) {
      return true;
    }
  }

  public abstract static class PatternMatchSamplingRule extends RateSamplingRule {
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

  public static final class TraceSamplingRule extends RateSamplingRule {
    private final Matcher serviceMatcher;
    private final Matcher operationMatcher;
    private final Matcher resourceMatcher;
    private final TagsMatcher tagsMatcher;

    public TraceSamplingRule(
        final String serviceGlob,
        final String operationGlob,
        final String resourceGlob,
        final Map<String, String> tags,
        final RateSampler sampler,
        final byte samplingMechanism) {
      super(sampler, samplingMechanism);

      serviceMatcher = Matchers.compileGlob(serviceGlob);
      operationMatcher = Matchers.compileGlob(operationGlob);
      resourceMatcher = Matchers.compileGlob(resourceGlob);
      tagsMatcher = TagsMatcher.create(tags);
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(T span) {
      return Matchers.matches(serviceMatcher, span.getServiceName())
          && Matchers.matches(operationMatcher, span.getOperationName())
          && Matchers.matches(resourceMatcher, span.getResourceName())
          && tagsMatcher.matches(span);
    }
  }

  public static final class SpanSamplingRule extends RateSamplingRule {
    private final Matcher serviceMatcher;
    private final Matcher operationMatcher;
    private final SimpleRateLimiter rateLimiter;

    public SpanSamplingRule(
        final String serviceName,
        final String operationName,
        final RateSampler sampler,
        final SimpleRateLimiter rateLimiter) {
      super(sampler);

      serviceMatcher = Matchers.compileGlob(serviceName);
      operationMatcher = Matchers.compileGlob(operationName);

      this.rateLimiter = rateLimiter;
    }

    @Override
    public <T extends CoreSpan<T>> boolean matches(T span) {
      return Matchers.matches(serviceMatcher, span.getServiceName())
          && Matchers.matches(operationMatcher, span.getOperationName());
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
