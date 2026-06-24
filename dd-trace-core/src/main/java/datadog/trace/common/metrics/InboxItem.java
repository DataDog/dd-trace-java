package datadog.trace.common.metrics;

import java.util.concurrent.CompletableFuture;

interface InboxItem {}

/**
 * Inbox-routed control message. Each subclass exposes a process-wide {@code static final} singleton
 * ({@link StopSignal#STOP}, {@link ReportSignal#REPORT}, {@link ClearSignal#CLEAR}) for the common
 * fire-and-forget case and is also directly instantiable when a caller needs to await handling.
 *
 * <p><b>Singletons are fire-and-forget.</b> The inherited {@link #future} is completed on first
 * handling by the aggregator thread and never reset, so a second posting of the same singleton
 * cannot signal completion to a fresh awaiter -- the future is already done. Callers that want
 * one-shot completion semantics (e.g. {@code forceReport()}) must allocate a fresh instance ({@code
 * new ReportSignal()}) rather than reusing the singleton.
 */
abstract class SignalItem implements InboxItem {
  final CompletableFuture<Boolean> future;

  public SignalItem() {
    this.future = new CompletableFuture<>();
  }

  void complete() {
    this.future.complete(true);
  }

  void ignore() {
    this.future.complete(false);
  }

  static final class StopSignal extends SignalItem {
    /** Fire-and-forget singleton. See class-level note on {@link SignalItem}. */
    static final StopSignal STOP = new StopSignal();

    private StopSignal() {}
  }

  static final class ReportSignal extends SignalItem {
    /** Fire-and-forget singleton; {@code forceReport()} allocates fresh instances. */
    static final ReportSignal REPORT = new ReportSignal();
  }

  /**
   * Posted from arbitrary threads (e.g. the Sink event thread during agent downgrade) so the
   * aggregator thread is the one that actually performs the table reset. Keeps {@link
   * AggregateTable} and {@code inbox.clear()} single-writer.
   */
  static final class ClearSignal extends SignalItem {
    /** Fire-and-forget singleton. See class-level note on {@link SignalItem}. */
    static final ClearSignal CLEAR = new ClearSignal();

    private ClearSignal() {}
  }
}
