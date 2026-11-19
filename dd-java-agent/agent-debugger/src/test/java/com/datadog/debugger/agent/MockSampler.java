package com.datadog.debugger.agent;

import datadog.trace.api.sampling.Sampler;

public class MockSampler implements Sampler {
  private final int numSamples;

  private int callCount;

  public MockSampler() {
    this(Integer.MAX_VALUE);
  }

  public MockSampler(int numSamples) {
    this.numSamples = numSamples;
  }

  @Override
  public boolean sample() {
    callCount++;
    return callCount <= numSamples;
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
