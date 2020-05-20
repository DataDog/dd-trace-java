package com.datadog.profiling.mlt;

import datadog.trace.core.util.ThreadStackAccess;
import org.junit.jupiter.api.Test;
import org.testng.Assert;

public class JMXSamplerTest {

  @Test
  public void sampler() {
    ThreadStackAccess.enableJmx();
    StackTraceSink sink = new JFRStackTraceSink();
    JMXSampler sampler = new JMXSampler(sink);
    sampler.addThreadId(Thread.currentThread().getId());
    sampler.removeThread(Thread.currentThread().getId());
    sampler.shutdown();
    byte[] buffer = sink.flush();
    Assert.assertNotNull(buffer);
    Assert.assertTrue(buffer.length > 0);
  }
}
