package stackstate.trace.common.sampling;

import stackstate.opentracing.STSSpan;

/** Sampler that always says yes... */
public class AllSampler extends AbstractSampler {

  @Override
  public boolean doSample(final STSSpan span) {
    return true;
  }

  @Override
  public String toString() {
    return "AllSampler { sample=true }";
  }
}
