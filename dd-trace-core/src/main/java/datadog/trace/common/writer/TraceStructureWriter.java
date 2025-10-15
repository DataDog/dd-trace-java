package datadog.trace.common.writer;

import datadog.environment.OperatingSystem;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpan;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TraceStructureWriter implements Writer {

  private static final Logger log = LoggerFactory.getLogger(TraceStructureWriter.class);
  private static final Pattern ARGS_DELIMITER = Pattern.compile(":", Pattern.LITERAL);

  private final PrintStream out;
  private final boolean debugLog;
  private final boolean includeResource;
  private final boolean includeService;

  public TraceStructureWriter() {
    this("", false);
  }

  public TraceStructureWriter(boolean debugLog) {
    this("", debugLog);
  }

  public TraceStructureWriter(String outputFile) {
    this(outputFile, false);
  }

  @SuppressForbidden
  public TraceStructureWriter(String outputFile, boolean debugLog) {
    boolean argsDebugLog = debugLog;
    boolean argsIncludeResource = false;
    boolean argsIncludeService = false;
    if (null == outputFile) {
      outputFile = "";
    }
    if (!outputFile.isEmpty() && outputFile.charAt(0) == ':') {
      outputFile = outputFile.substring(1);
    }
    try {
      String[] args = parseArgs(outputFile);
      String fileName = args[0];
      this.out = fileName.isEmpty() ? System.err : new PrintStream(new FileOutputStream(fileName));
      for (int i = 1; i < args.length; i++) {
        switch (args[i].toLowerCase(Locale.ROOT)) {
          case "includeresource":
            argsIncludeResource = true;
            break;
          case "includeservice":
            argsIncludeService = true;
            break;
          case "debuglog":
            argsDebugLog = true;
            break;
          default:
            log.warn("Illegal TraceStructureWriter argument '{}'", args[i]);
            break;
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to create trace structure writer from " + outputFile, e);
    }
    this.debugLog = argsDebugLog;
    this.includeResource = argsIncludeResource;
    this.includeService = argsIncludeService;
  }

  private static String[] parseArgs(String outputFile) {
    return parseArgs(outputFile, OperatingSystem.isWindows());
  }

  // package visibility for testing
  static String[] parseArgs(String outputFile, boolean windows) {
    String[] args = ARGS_DELIMITER.split(outputFile);
    // Check Windows absolute paths (<drive>:<path>) as column is used as arg delimiter
    if (windows
        && args.length > 1
        && args[0].length() == 1
        && (args[1].startsWith("\\") || args[1].startsWith("/"))) {
      String[] windowsArgs = new String[args.length - 1];
      windowsArgs[0] = args[0] + ":" + args[1];
      System.arraycopy(args, 2, windowsArgs, 1, args.length - 2);
      args = windowsArgs;
    }
    return args;
  }

  @Override
  public void write(List<DDSpan> trace) {
    if (trace.isEmpty()) {
      output("[]", null, DDSpanId.ZERO);
    } else {
      DDTraceId traceId = trace.get(0).getTraceId();
      long rootSpanId = trace.get(0).getSpanId();
      Map<Long, Node> nodesById = new HashMap<>();
      // index the tree
      for (DDSpan span : trace) {
        if (span.getLocalRootSpan() == span) {
          rootSpanId = span.getSpanId();
        }
        nodesById.put(span.getSpanId(), new Node(span, includeService, includeResource));
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
        if (rootSpanId != span.getSpanId()) {
          Node parent = nodesById.get(span.getParentId());
          if (null == parent) {
            String message =
                "Trace "
                    + traceId
                    + " has broken parent link at "
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
            // Add this to the rootSpanId and continue so we can see the broken trace
            parent = nodesById.get(rootSpanId);
          }
          parent.addChild(nodesById.get(span.getSpanId()));
        }
      }
      output(String.valueOf(nodesById.get(rootSpanId)), traceId, rootSpanId);
    }
  }

  private void output(String trace, DDTraceId traceId, long rootSpanId) {
    out.println(trace);
    if (debugLog && log.isDebugEnabled()) {
      StringBuilder start = new StringBuilder();
      if (traceId != null) {
        start.append("t_id=").append(traceId);
      }
      if (rootSpanId != DDSpanId.ZERO) {
        if (start.length() > 0) {
          start.append(", ");
        }
        start.append("s_id=").append(DDSpanId.toString(rootSpanId));
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

  @SuppressForbidden
  @Override
  public void close() {
    if (out != System.err) {
      out.close();
    }
  }

  @Override
  public void incrementDropCounts(int spanCount) {}

  private static final class Node {
    private final CharSequence operationName;
    private final CharSequence resourceName;
    private final CharSequence serviceName;
    private final List<Node> children = new ArrayList<>();

    private Node(DDSpan span, boolean includeService, boolean includeResource) {
      this.operationName = span.getOperationName();
      this.resourceName = includeResource ? span.getResourceName() : null;
      this.serviceName = includeService ? span.getServiceName() : null;
    }

    public void addChild(Node child) {
      children.add(child);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append('[');
      if (null != serviceName) {
        sb.append(serviceName).append(':');
      }
      sb.append(operationName);
      if (null != resourceName) {
        sb.append(':').append(resourceName);
      }
      for (Node node : children) {
        sb.append(node);
      }
      return sb.append(']').toString();
    }
  }
}
