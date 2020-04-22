package com.datadog.profiling.sampler;

import com.datadog.profiling.jfr.JfrChunkWriter;
import com.datadog.profiling.jfr.JfrWriter;
import com.datadog.profiling.jfr.Type;
import com.datadog.profiling.jfr.TypedValue;
import com.datadog.profiling.jfr.Types;
import java.io.IOException;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class SamplerWriter {
  static final String EVENT_NAME = "datadog.SamplerEvent";

  private final Type sampleEventType;
  private final JfrWriter jfrWriter;
  private final AtomicReference<JfrChunkWriter> chunkWriter = new AtomicReference<>();

  public SamplerWriter() {
    this.jfrWriter = new JfrWriter();
    this.sampleEventType = jfrWriter.registerEventType(EVENT_NAME);
    this.chunkWriter.set(jfrWriter.newChunk());
  }

  public void dump(Path target) throws IOException {
    byte[] data = chunkWriter.getAndSet(jfrWriter.newChunk()).dump();

    Files.write(target, data);
  }

  public void writeThreadSample(ThreadInfo threadInfo) {
    if (threadInfo.getStackTrace().length == 0) {
      return;
    }

    chunkWriter
        .get()
        .writeEvent(
            sampleEventType.asValue(
                builder -> {
                  builder
                      .putField("startTime", System.currentTimeMillis() * 1_000_000L)
                      .putField("eventThread", getThread(threadInfo))
                      .putField("stackTrace", getStackTrace(threadInfo));
                }));
  }

  private TypedValue getThread(ThreadInfo threadInfo) {
    return jfrWriter
        .getJdkType(Types.JDK.THREAD)
        .asValue(
            builder -> {
              builder
                  .putField("javaName", threadInfo.getThreadName())
                  .putField("osName", threadInfo.getThreadName())
                  .putField("osThreadId", threadInfo.getThreadId());
            });
  }

  private TypedValue getStackTrace(ThreadInfo threadInfo) {
    return jfrWriter
        .getJdkType(Types.JDK.STACK_TRACE)
        .asValue(
            builder -> {
              builder.putField("truncated", false).putField("frames", getFrames(threadInfo));
            });
  }

  private TypedValue[] getFrames(ThreadInfo threadInfo) {
    StackTraceElement[] elements = threadInfo.getStackTrace();
    TypedValue[] frames = new TypedValue[elements.length];
    for (int i = 0; i < elements.length; i++) {
      frames[i] = getFrame(elements[i]);
    }
    return frames;
  }

  private TypedValue getFrame(StackTraceElement element) {
    return jfrWriter
        .getJdkType(Types.JDK.STACK_FRAME)
        .asValue(
            builder -> {
              builder
                  .putField("method", getMethod(element))
                  .putField("lineNumber", element.getLineNumber());
            });
  }

  private TypedValue getMethod(StackTraceElement element) {
    return jfrWriter
        .getJdkType(Types.JDK.METHOD)
        .asValue(
            builder -> {
              builder
                  .putField("type", getClass(element))
                  .putField("name", element.getMethodName())
                  .putField("descriptor", "()V")
                  .putField("modifiers", element.isNativeMethod() ? Modifier.NATIVE : -1);
            });
  }

  private TypedValue getClass(StackTraceElement element) {
    String fqn = element.getClassName();
    int idx = fqn.lastIndexOf('.');
    String pkgName = idx > -1 ? fqn.substring(idx + 1) : "";
    return jfrWriter
        .getJdkType(Types.JDK.CLASS)
        .asValue(
            builder -> {
              builder
                  .putField(
                      "package",
                      pkgBuilder -> {
                        pkgBuilder.putField("name", pkgName);
                      })
                  .putField("name", fqn);
            });
  }
}
