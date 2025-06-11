package com.datadog.appsec.api.security

import spock.lang.Specification

import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ApiSecuritySamplerTest extends Specification {

  static class SamplerArgs {
    int maxItemCount = 8
    int intervalSeconds = 30
    long zero = 42L
    TestClock clock = new TestClock()
    Executor executor = Executors.newSingleThreadExecutor()
  }

  ApiSecuritySampler buildSampler(SamplerArgs args = new SamplerArgs()) {
    return new ApiSecuritySampler(args.maxItemCount, args.intervalSeconds, args.zero, args.clock, args.executor)
  }

  void 'test single entry and no concurrency'() {
    setup:
    int intervalSeconds = 30
    TestClock clock = new TestClock()
    ApiSecuritySampler sampler = buildSampler(new SamplerArgs(intervalSeconds: intervalSeconds, clock: clock))

    expect:
    sampler.sample(1L)
    !sampler.sample(1L)
    clock.inc(1)
    !sampler.sample(1L)
    // Increment time to just one second before the next interval
    clock.inc(intervalSeconds - 2)
    !sampler.sample(1L)
    // Increment time to the next interval (exactly)
    clock.inc(1)
    sampler.sample(1L)
    !sampler.sample(1L)
  }

  void 'test full map and no concurrency and no rebuilds'() {
    setup:
    int maxItemCount = 8
    int intervalSeconds = 30
    TestClock clock = new TestClock()
    // Inhibit map rebuilding
    Executor executor = Stub(Executor)
    ApiSecuritySampler sampler = buildSampler(new SamplerArgs(maxItemCount: maxItemCount, intervalSeconds: intervalSeconds, clock: clock, executor: executor))

    expect:
    for (int i = 0; i < maxItemCount * 2; i++) {
      assert sampler.sample(i)
    }
    for (int i = 0; i < maxItemCount * 2; i++) {
      assert !sampler.sample(i)
    }
    assert !sampler.sample(Long.MAX_VALUE)
    clock.inc(intervalSeconds)
    for (int i = 0; i < maxItemCount * 2; i++) {
      assert sampler.sample(i)
    }
    for (int i = 0; i < maxItemCount * 2; i++) {
      assert !sampler.sample(i)
    }
    assert !sampler.sample(Long.MAX_VALUE)
  }
}
