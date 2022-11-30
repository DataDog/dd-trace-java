package datadog.trace.bootstrap.debugger;

import java.util.List;

public interface SummaryBuilder {
  void addEntry(Snapshot.CapturedContext entry);

  void addExit(Snapshot.CapturedContext exit);

  void addLine(Snapshot.CapturedContext line);

  void addStack(List<CapturedStackFrame> stack);

  String build();
}
