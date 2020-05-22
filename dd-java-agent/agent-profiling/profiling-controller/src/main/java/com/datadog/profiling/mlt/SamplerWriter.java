package com.datadog.profiling.mlt;

import com.datadog.profiling.jfr.Chunk;
import com.datadog.profiling.jfr.Recording;
import com.datadog.profiling.jfr.Type;
import com.datadog.profiling.jfr.TypedValue;
import com.datadog.profiling.jfr.Types;
import java.io.IOException;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TODO This is an example of how the {@linkplain Recording} can be used to write JMX generated
 * stacktraces.
 */
public final class SamplerWriter {
  static final String CONTEXT_EVENT_NAME = "datadog.TraceContextEvent";
  static final String SAMPLER_EVENT_NAME = "datadog.SamplerEvent";

  private final Type sampleEventType;
  private final Type contextEventType;
  private final Recording recording;
  private final AtomicReference<Chunk> chunkWriter = new AtomicReference<>();

  public SamplerWriter() {
    this.recording = new Recording();
    this.contextEventType = registerContextEventType();
    this.sampleEventType = recording.registerEventType(SAMPLER_EVENT_NAME);
    this.chunkWriter.set(recording.newChunk());
  }

  public byte[] flush() {
    return chunkWriter.getAndSet(recording.newChunk()).finish();
  }

  public void dump(Path target) throws IOException {
    byte[] data = flush();

    Files.write(target, data);
  }

  public void writeContextEvent(SamplerContext context) {
    if (context == null) {
      return;
    }
    chunkWriter
        .get()
        .writeEvent(
            contextEventType.asValue(
                builder -> {
                  builder
                      .putField("startTime", System.nanoTime())
                      .putField("eventThread", getThread(context.getThread()))
                      .putField("traceId", context.getTraceId());
                }));
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
                      .putField("startTime", System.nanoTime())
                      .putField("eventThread", getThread(threadInfo))
                      .putField("stackTrace", getStackTrace(threadInfo));
                }));
  }

  private Type registerContextEventType() {
    return recording.registerEventType(
        CONTEXT_EVENT_NAME,
        builder -> {
          builder.addField("traceId", Types.Builtin.STRING);
        });
  }

  private TypedValue getThread(Thread thread) {
    return recording
        .getType(Types.JDK.THREAD)
        .asValue(
            builder -> {
              builder
                  .putField("javaName", thread.getName())
                  .putField("osName", thread.getName())
                  .putField("osThreadId", thread.getId());
            });
  }

  private TypedValue getThread(ThreadInfo threadInfo) {
    return recording
        .getType(Types.JDK.THREAD)
        .asValue(
            builder -> {
              builder
                  .putField("javaName", threadInfo.getThreadName())
                  .putField("osName", threadInfo.getThreadName())
                  .putField("osThreadId", threadInfo.getThreadId());
            });
  }

  private TypedValue getStackTrace(ThreadInfo threadInfo) {
    return recording
        .getType(Types.JDK.STACK_TRACE)
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
    return recording
        .getType(Types.JDK.STACK_FRAME)
        .asValue(
            builder -> {
              builder
                  .putField("method", getMethod(element))
                  .putField("lineNumber", element.getLineNumber());
            });
  }

  private TypedValue getMethod(StackTraceElement element) {
    return recording
        .getType(Types.JDK.METHOD)
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
    String pkgName = getPackageName(fqn);
    return recording
        .getType(Types.JDK.CLASS)
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

  static String getPackageName(String fqn) {
    int idx = fqn.lastIndexOf('.');
    return idx > -1 ? fqn.substring(0, idx) : "";
  }
}
