package com.datadog.appsec.api.security

import groovy.transform.CompileStatic

@CompileStatic
class TestClock implements ApiSecuritySampler.MonotonicClock {

  private int time = 0

  int inc(final int delta) {
    assert delta >= 0 : "Delta must be non-negative"
    time += delta
    return time
  }

  @Override
  int now() {
    return time
  }
}
