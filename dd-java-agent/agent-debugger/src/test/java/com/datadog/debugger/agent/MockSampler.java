package com.datadog.debugger.agent;

import datadog.trace.api.sampling.Sampler;

public class MockSampler implements Sampler {

  private int callCount;

  @Override
  public boolean sample() {
    callCount++;
    return true;
  }

  @Override
  public boolean keep() {
    return false;
  }

  @Override
  public boolean drop() {
    return false;
  }

  public int getCallCount() {
    return callCount;
  }
}
