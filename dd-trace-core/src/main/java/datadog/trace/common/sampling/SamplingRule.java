package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;
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

  public static final class AlwaysMatchesSamplingRule<T extends CoreSpan<T>>
      extends SamplingRule<T> {

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

  public static final class ServiceSamplingRule<T extends CoreSpan<T>>
      extends PatternMatchSamplingRule<T> {
    public ServiceSamplingRule(final String regex, final RateSampler<T> sampler) {
      super(regex, sampler);
    }

    @Override
    protected String getRelevantString(final T span) {
      return span.getServiceName();
    }
  }

  public static final class OperationSamplingRule<T extends CoreSpan<T>>
      extends PatternMatchSamplingRule<T> {
    public OperationSamplingRule(final String regex, final RateSampler<T> sampler) {
      super(regex, sampler);
    }

    @Override
    protected CharSequence getRelevantString(final T span) {
      return span.getOperationName();
    }
  }
}
