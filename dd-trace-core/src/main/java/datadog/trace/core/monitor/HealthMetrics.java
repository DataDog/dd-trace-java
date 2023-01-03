package datadog.trace.core.monitor;

import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import java.util.List;

/**
 * Callback for monitoring the health of the DDAgentWriter. Provides hooks for major lifecycle
 * events...
 *
 * <ul>
 *   <li>start
 *   <li>shutdown
 *   <li>publishing to disruptor
 *   <li>serializing
 *   <li>sending to agent
 * </ul>
 */
public abstract class HealthMetrics implements AutoCloseable {
  public static HealthMetrics NO_OP = new NoOpHealthMetrics();

  public void start() {};

  public void onStart(final int queueCapacity) {};

  public void onShutdown(final boolean flushSuccess) {};

  public void onPublish(final List<DDSpan> trace, final int samplingPriority) {};

  public void onFailedPublish(final int samplingPriority) {};

  public void onPartialPublish(final int numberOfDroppedSpans) {};

  public void onScheduleFlush(final boolean previousIncomplete) {};

  public void onFlush(final boolean early) {};

  public void onPartialFlush(final int sizeInBytes) {};

  public void onSerialize(final int serializedSizeInBytes) {};

  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {};

  public void onCreateSpan() {};

  public void onCreateTrace() {};

  public void onCreateManualTrace() {};

  public void onCancelContinuation() {};

  public void onFinishContinuation() {};

  public void onSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {};

  public void onFailedSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {};

  @Override
  public void close() {};
}
