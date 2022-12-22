package datadog.trace.core.monitor;

import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import java.util.List;

public class NoOpHealthMetrics implements HealthMetrics, AutoCloseable {
  @Override
  public void start() {}

  @Override
  public void onStart(int queueCapacity) {}

  @Override
  public void onShutdown(boolean flushSuccess) {}

  @Override
  public void onPublish(List<DDSpan> trace, int samplingPriority) {}

  @Override
  public void onFailedPublish(int samplingPriority) {}

  @Override
  public void onPartialPublish(int numberOfDroppedSpans) {}

  @Override
  public void onScheduleFlush(boolean previousIncomplete) {}

  @Override
  public void onFlush(boolean early) {}

  @Override
  public void onPartialFlush(int sizeInBytes) {}

  @Override
  public void onSerialize(int serializedSizeInBytes) {}

  @Override
  public void onFailedSerialize(List<DDSpan> trace, Throwable optionalCause) {}

  @Override
  public void onCreateSpan() {}

  @Override
  public void onCreateTrace() {}

  @Override
  public void onCreateManualTrace() {}

  @Override
  public void onCancelContinuation() {}

  @Override
  public void onFinishContinuation() {}

  @Override
  public void onSend(int traceCount, int sizeInBytes, RemoteApi.Response response) {}

  @Override
  public void onFailedSend(int traceCount, int sizeInBytes, RemoteApi.Response response) {}

  @Override
  public void close() {}
}
