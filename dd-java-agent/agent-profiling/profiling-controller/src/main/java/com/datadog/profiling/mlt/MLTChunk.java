package com.datadog.profiling.mlt;

import lombok.Data;

import java.util.List;

@Data
public class MLTChunk {
  private final byte version;
  private final int size;
  private final long startTime;
  private final long duration;
  private final long threadId;
  private final String threadName;
  private final List<StackElement1> stacks;
}
