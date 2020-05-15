package com.datadog.profiling.mlt;

import java.util.List;

public class JFRStackTraceSink implements StackTraceSink {
  @Override
  public void dump(String id, List<StackTraceElement[]> stackTraces) {
    // TODO write into JFR file
  }
}
