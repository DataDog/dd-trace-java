package datadog.trace.common.metrics;

import java.util.concurrent.CompletableFuture;

interface InboxItem {}

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
    static final StopSignal STOP = new StopSignal();

    private StopSignal() {}
  }

  static final class ReportSignal extends SignalItem {
    static final ReportSignal REPORT = new ReportSignal();
  }
}
