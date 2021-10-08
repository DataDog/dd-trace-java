package datadog.trace.api.sampling;

public class ConstantSampler implements Sampler {

  private final boolean constant;

  public ConstantSampler(boolean constant) {
    this.constant = constant;
  }

  @Override
  public boolean sample() {
    return constant;
  }

  @Override
  public boolean keep() {
    return true;
  }

  @Override
  public boolean drop() {
    return false;
  }
}
