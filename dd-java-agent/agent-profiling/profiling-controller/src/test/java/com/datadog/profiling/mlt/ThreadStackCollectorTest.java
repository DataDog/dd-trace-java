package com.datadog.profiling.mlt;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

class ThreadStackCollectorTest {

  @Test
  void sample() throws Exception {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    Map<Long, ScopeStackCollector> samplerMap = new HashMap<>();
    long[] tids = threadMXBean.getAllThreadIds();
    for (long tid : tids) {
      ThreadInfo threadInfo = threadMXBean.getThreadInfo(tid);
      samplerMap.put(tid, new ThreadStackCollector(tid, threadInfo.getThreadName()).startScope("scope"));
    }
    for (int i = 0; i < 10000; i++) {
      for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
        ScopeStackCollector sampler = samplerMap.get(ti.getThreadId());
        if (sampler != null) {
          sampler.sample(ti.getStackTrace());
        }
      }
      Thread.sleep(1);
    }
    byte[] allData = new byte[0];
    for (long tid : tids) {
      byte[] data = samplerMap.get(tid).end();
      int pos = allData.length;
      allData = Arrays.copyOf(allData, allData.length + data.length);
      System.arraycopy(data, 0, allData, pos, data.length);
//      samplerMap.get(tid).printStacktraces();
    }
    System.out.println("===> data len: " + allData.length);
    System.out.println(Base64.getEncoder().encodeToString(allData));
  }
}
