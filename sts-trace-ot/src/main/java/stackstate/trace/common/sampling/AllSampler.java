package stackstate.trace.common.sampling;

import stackstate.opentracing.DDSpan;

/** Sampler that always says yes... */
public class AllSampler extends AbstractSampler {

  @Override
  public boolean doSample(final DDSpan span) {
    return true;
  }

  @Override
  public String toString() {
    return "AllSampler { sample=true }";
  }
}
