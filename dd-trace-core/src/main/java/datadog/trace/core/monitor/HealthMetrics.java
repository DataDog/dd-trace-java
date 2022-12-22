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
public interface HealthMetrics extends AutoCloseable {
  HealthMetrics NO_OP = new NoOpHealthMetrics();

  void start();

  void onStart(final int queueCapacity);

  void onShutdown(final boolean flushSuccess);

  void onPublish(final List<DDSpan> trace, final int samplingPriority);

  void onFailedPublish(final int samplingPriority);

  void onPartialPublish(final int numberOfDroppedSpans);

  void onScheduleFlush(final boolean previousIncomplete);

  void onFlush(final boolean early);

  void onPartialFlush(final int sizeInBytes);

  void onSerialize(final int serializedSizeInBytes);

  void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause);

  void onCreateSpan();

  void onCreateTrace();

  void onCreateManualTrace();

  void onCancelContinuation();

  void onFinishContinuation();

  void onSend(final int traceCount, final int sizeInBytes, final RemoteApi.Response response);

  void onFailedSend(final int traceCount, final int sizeInBytes, final RemoteApi.Response response);

  void close();
}
