package com.datadog.profiling.mlt;

import datadog.trace.core.util.ThreadStackAccess;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class JMXSamplerTest {

  @Test
  public void sampler() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    ThreadScopeMapper mapper = new ThreadScopeMapper();
    StackTraceSink sink = new JFRStackTraceSink();
    JMXSampler sampler = new JMXSampler(sink, mapper);
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

  @Test
  public void emptySampler() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    ThreadScopeMapper mapper = new ThreadScopeMapper();
    StackTraceSink sink = new JFRStackTraceSink();
    JMXSampler sampler = new JMXSampler(sink, mapper);
    Thread.sleep(100);
    sampler.shutdown();
    byte[] buffer = sink.flush();
    Assert.assertNotNull(buffer);
    Assert.assertTrue(buffer.length > 0);
  }
}
