package com.datadog.debugger.agent;

import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.SummaryBuilder;
import java.util.List;

public class LogMessageTemplateSummaryBuilder implements SummaryBuilder {
  private final LogProbe logProbe;

  public LogMessageTemplateSummaryBuilder(LogProbe logProbe) {
    this.logProbe = logProbe;
  }

  @Override
  public void addEntry(Snapshot.CapturedContext entry) {
    executeExpressions(entry);
  }

  @Override
  public void addExit(Snapshot.CapturedContext exit) {
    executeExpressions(exit);
  }

  @Override
  public void addLine(Snapshot.CapturedContext line) {
    executeExpressions(line);
  }

  @Override
  public void addStack(List<CapturedStackFrame> stack) {}

  @Override
  public String build() {
    if (logProbe.getSegments() == null) {
      return "This is a dynamically created log message.";
    }
    StringBuilder sb = new StringBuilder();
    for (LogProbe.Segment segment : logProbe.getSegments()) {
      if (segment.getStr() != null) {
        sb.append(segment.getStr());
      } else {
        sb.append(segment.getParsedExpr().getResult().getValue());
      }
    }
    return sb.toString();
  }

  private void executeExpressions(Snapshot.CapturedContext entry) {
    if (logProbe.getSegments() == null) {
      return;
    }
    for (LogProbe.Segment segment : logProbe.getSegments()) {
      ValueScript parsedExr = segment.getParsedExpr();
      if (parsedExr != null) {
        parsedExr.execute(entry);
      }
    }
  }
}
