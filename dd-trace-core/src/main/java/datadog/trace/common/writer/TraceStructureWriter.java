package datadog.trace.common.writer;

import datadog.trace.api.DDId;
import datadog.trace.core.DDSpan;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TraceStructureWriter implements Writer {

  private final PrintStream out;
  private final boolean debugLog;

  public TraceStructureWriter() {
    this("", false);
  }

  public TraceStructureWriter(boolean debugLog) {
    this("", debugLog);
  }

  public TraceStructureWriter(String outputFile) {
    this(outputFile, false);
  }

  public TraceStructureWriter(String outputFile, boolean debugLog) {
    this.debugLog = debugLog;
    try {
      this.out =
          outputFile.isEmpty() || outputFile.equals(":")
              ? System.err
              : new PrintStream(new FileOutputStream(new File(outputFile.replace(":", ""))));
    } catch (IOException e) {
      throw new RuntimeException("Failed to create trace structure writer from " + outputFile, e);
    }
  }

  @Override
  public void write(List<DDSpan> trace) {
    if (trace.isEmpty()) {
      output("[]", null, null);
    } else {
      DDId traceId = trace.get(0).getTraceId();
      DDId rootSpanId = trace.get(0).getSpanId();
      Map<DDId, Node> nodesById = new HashMap<>();
      // index the tree
      for (DDSpan span : trace) {
        if (DDId.ZERO.equals(span.getParentId())) {
          rootSpanId = span.getSpanId();
        }
        nodesById.put(span.getSpanId(), new Node(span));
      }
      // build the tree
      for (DDSpan span : trace) {
        if (!traceId.equals(span.getTraceId())) {
          String message =
              "Trace "
                  + traceId
                  + " has broken trace link at "
                  + span.getSpanId()
                  + "("
                  + span.getOperationName()
                  + ")"
                  + "->"
                  + span.getTraceId();
          out.println("ERROR: " + message);
          if (debugLog) {
            log.error(message);
          }
          return;
        }
        if (!rootSpanId.equals(span.getSpanId())) {
          Node parent = nodesById.get(span.getParentId());
          if (null == parent) {
            String message =
                "Trace "
                    + traceId
                    + " has broken link at "
                    + span.getSpanId()
                    + "("
                    + span.getOperationName()
                    + ")"
                    + "->"
                    + span.getParentId();
            out.println("ERROR: " + message);
            if (debugLog) {
              log.error(message);
            }
            return;
          }
          parent.addChild(nodesById.get(span.getSpanId()));
        }
      }
      output(String.valueOf(nodesById.get(rootSpanId)), traceId, rootSpanId);
    }
  }

  private void output(String trace, DDId traceId, DDId rootSpanId) {
    out.println(trace);
    if (debugLog && log.isDebugEnabled()) {
      StringBuilder start = new StringBuilder();
      if (traceId != null) {
        start.append("t_id=").append(traceId);
      }
      if (rootSpanId != null) {
        if (start.length() > 0) {
          start.append(", ");
        }
        start.append("s_id=").append(rootSpanId);
      }
      if (start.length() > 0) {
        start.append(" -> ");
      }
      log.debug("{}wrote {}", start, trace);
    }
  }

  @Override
  public void start() {}

  @Override
  public boolean flush() {
    out.flush();
    return true;
  }

  @Override
  public void close() {
    if (out != System.err) {
      out.close();
    }
  }

  @Override
  public void incrementTraceCount() {}

  private static final class Node {
    private final CharSequence operationName;
    private final List<Node> children = new ArrayList<>();

    private Node(DDSpan span) {
      this.operationName = span.getOperationName();
    }

    public void addChild(Node child) {
      children.add(child);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("[").append(operationName);
      for (Node node : children) {
        sb.append(node);
      }
      return sb.append("]").toString();
    }
  }
}
