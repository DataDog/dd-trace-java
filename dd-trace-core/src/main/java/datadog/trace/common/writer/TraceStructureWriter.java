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

public class TraceStructureWriter implements Writer {

  private final PrintStream out;

  public TraceStructureWriter() {
    this("");
  }

  public TraceStructureWriter(String outputFile) {
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
      out.println("[]");
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
        if (!rootSpanId.equals(span.getSpanId())) {
          Node parent = nodesById.get(span.getParentId());
          if (null == parent) {
            out.println(
                "ERROR: Trace "
                    + traceId
                    + " has broken link at "
                    + span.getSpanId()
                    + "("
                    + span.getOperationName()
                    + ")"
                    + "->"
                    + span.getParentId());
            return;
          }
          parent.addChild(nodesById.get(span.getSpanId()));
        }
      }
      out.println(nodesById.get(rootSpanId));
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
