package com.datadog.profiling.mlt;

import datadog.trace.core.util.ThreadStackAccess;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class JMXSamplerTest {

  @Test
  public void sampler() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    StackTraceSink sink = new JFRStackTraceSink();
    JMXSampler sampler = new JMXSampler(sink);
    sampler.addThreadId(Thread.currentThread().getId());
    sampler.addThreadId(1);
    Thread.sleep(100);
    sampler.removeThread(1);
    sampler.removeThread(Thread.currentThread().getId());
    sampler.shutdown();
    byte[] buffer = sink.flush();
    Assert.assertNotNull(buffer);
    Assert.assertTrue(buffer.length > 0);
  }
}
