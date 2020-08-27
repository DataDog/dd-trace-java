package com.datadog.mlt.sampler;

import com.datadog.mlt.io.ConstantPool;
import com.datadog.mlt.io.FrameElement;
import com.datadog.mlt.io.FrameSequence;
import com.datadog.mlt.io.IMLTChunk;
import com.datadog.mlt.io.MLTChunkCollector;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ScopeStackCollector extends MLTChunkCollector {

  private static final boolean LOGGING_STACKCOUNT = Boolean.getBoolean("mlt.log.stackcount");

  private static final byte VERSION = (byte) 0;

  private final ScopeManager threadStacktraceCollector;

  @Getter private final long startTime;
  private final long startTimeNs;

  @Getter private final String scopeId;

  ScopeStackCollector(
      @NonNull String scopeId,
      ScopeManager threadStacktraceCollector,
      long startTimeNs,
      long startTimeEpoch,
      ConstantPool<String> stringPool,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameSequence> stackPool) {
    super(null /*new Throwable()*/, stringPool, framePool, stackPool);
    this.scopeId = scopeId;
    this.threadStacktraceCollector = threadStacktraceCollector;
    startTime = startTimeEpoch;
    this.startTimeNs = startTimeNs;
  }

  @Override
  public long getDuration() {
    return TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTimeNs, TimeUnit.NANOSECONDS);
  }

  @Override
  public byte getVersion() {
    return VERSION;
  }

  @Override
  public long getThreadId() {
    return threadStacktraceCollector.getThreadId();
  }

  @Override
  public String getThreadName() {
    return threadStacktraceCollector.getThreadName();
  }

  public <T> T end(Function<IMLTChunk, T> onEnd) {
    int stackCount = getStackCount();
    if (LOGGING_STACKCOUNT && stackCount > 0) {
      log.info("end scope(traceid)[{}] stacktraces[{}]", scopeId, stackCount);
    }
    return onEnd.apply(threadStacktraceCollector.endScope(this));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ScopeStackCollector that = (ScopeStackCollector) o;
    return scopeId.equals(that.scopeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scopeId);
  }
}
