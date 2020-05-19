package com.datadog.profiling.mlt;

import java.lang.management.ThreadInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JFRStackTraceSink implements StackTraceSink {
  private final SamplerWriter writer = new SamplerWriter();

  @Override
  public void write(String id, ThreadInfo[] threadInfos) {
    for (ThreadInfo threadInfo : threadInfos) {
      writer.writeThreadSample(threadInfo);
    }
  }

  @Override
  public byte[] flush() {
    return writer.flush();
  }
}
