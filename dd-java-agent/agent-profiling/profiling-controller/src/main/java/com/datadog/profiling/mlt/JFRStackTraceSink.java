package com.datadog.profiling.mlt;

import java.io.IOException;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JFRStackTraceSink implements StackTraceSink {
  private final Path target;
  private final SamplerWriter writer = new SamplerWriter();

  public JFRStackTraceSink(Path target) {
    this.target = target;
  }

  @Override
  public void dump(String id, List<ThreadInfo> threadInfos) {
    for (ThreadInfo threadInfo : threadInfos) {
      writer.writeThreadSample(threadInfo);
    }
    try {
      writer.dump(target);
    } catch (IOException ex) {
      log.warn("Cannot dump stack traces into " + target.toString(), ex);
    }
  }
}
