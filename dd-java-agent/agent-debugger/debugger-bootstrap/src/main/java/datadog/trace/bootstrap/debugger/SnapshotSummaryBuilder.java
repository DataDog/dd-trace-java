package datadog.trace.bootstrap.debugger;

import static java.util.stream.Collectors.*;

import datadog.trace.bootstrap.debugger.Snapshot.CapturedValue;
import datadog.trace.bootstrap.debugger.Snapshot.ProbeLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Helper class for generating snapshot summaries */
public class SnapshotSummaryBuilder {
  private final Snapshot.ProbeDetails probe;
  private String arguments;
  private String method;
  private String returnValue;
  private String locals;

  public SnapshotSummaryBuilder(Snapshot.ProbeDetails probe) {
    this.probe = probe;
  }

  public void addEntry(Snapshot.CapturedContext entry) {
    if (entry == null) {
      return;
    }
    if (entry.getArguments() != null) {
      arguments = formatCapturedValues(entry.getArguments());
    }
  }

  public void addExit(Snapshot.CapturedContext exit) {
    if (exit == null) {
      return;
    }
    if (exit.getLocals() == null) {
      return;
    }
    locals = formatCapturedValues(removeReturnFromLocals(exit.getLocals()));
    CapturedValue capturedReturnValue = exit.getLocals().get("@return");
    if (capturedReturnValue != null) {
      returnValue = String.valueOf(capturedReturnValue.getValue());
    }
  }

  public void addLine(Snapshot.CapturedContext line) {
    if (line == null) {
      return;
    }
    if (line.getArguments() != null) {
      arguments = formatCapturedValues(line.getArguments());
    }
    if (line.getLocals() != null) {
      locals = formatCapturedValues(line.getLocals());
    }
  }

  public void addStack(List<CapturedStackFrame> stack) {
    method = formatMethod(stack, probe.getLocation());
  }

  public String build() {
    StringBuilder sb = new StringBuilder();
    if (method == null) {
      method = formatMethod(probe.getLocation());
    }
    sb.append(method);
    sb.append("(");
    if (arguments != null) {
      sb.append(arguments);
    }
    sb.append(")");
    if (returnValue != null) {
      sb.append(": ").append(returnValue);
    }
    if (locals != null && !locals.isEmpty()) {
      sb.append("\n").append(locals);
    }
    return sb.toString();
  }

  private static String formatMethod(List<CapturedStackFrame> stack, ProbeLocation probeLocation) {
    if (stack != null && stack.size() > 0) {
      // we first try to use the top frame on the stacktrace, if available
      CapturedStackFrame topFrame = stack.get(0);
      return formatMethod(topFrame);
    } else if (probeLocation != null) {
      // if the stacktrace is not available we use the probe location
      return formatMethod(probeLocation);
    } else {
      // we should never get here, but just in case we return null instead of throwing
      return null;
    }
  }

  private static String formatMethod(CapturedStackFrame stackFrame) {
    if (stackFrame.getFunction() != null) {
      List<String> classAndMethod = getClassAndMethod(stackFrame.getFunction());
      if (classAndMethod.size() == 2) {
        return classAndMethod.get(1) + "." + classAndMethod.get(0);
      } else if (classAndMethod.size() == 1) {
        return classAndMethod.get(0);
      } else {
        return stackFrame.getFunction();
      }
    } else {
      return stackFrame.getFileName();
    }
  }

  private static List<String> getClassAndMethod(String stackFrameFunction) {
    int firstParenIdx = stackFrameFunction.indexOf('(');
    if (firstParenIdx >= 0) {
      stackFrameFunction = stackFrameFunction.substring(0, firstParenIdx);
    }
    int lastDotIdx = stackFrameFunction.lastIndexOf('.');
    if (lastDotIdx == -1) {
      return Collections.singletonList(stackFrameFunction);
    }
    List<String> results = new ArrayList<>();
    while (lastDotIdx > -1 && results.size() < 2) {
      String part = stackFrameFunction.substring(lastDotIdx + 1);
      results.add(part);
      stackFrameFunction = stackFrameFunction.substring(0, lastDotIdx);
      lastDotIdx = stackFrameFunction.lastIndexOf('.');
    }
    return results;
  }

  private static String formatMethod(ProbeLocation probeLocation) {
    if (probeLocation.getType() != null && probeLocation.getMethod() != null) {
      // parse out the class name
      String fqn = probeLocation.getType();
      String className = fqn.substring(fqn.lastIndexOf('.') + 1);
      return className + "." + probeLocation.getMethod();
    } else if (probeLocation.getMethod() != null) {
      return probeLocation.getMethod();
    } else {
      return probeLocation.getFile() + ":" + probeLocation.getLines();
    }
  }

  private static String formatCapturedValues(Map<String, CapturedValue> capturedValues) {
    return capturedValues.entrySet().stream()
        .sorted(Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + entry.getValue().getValue())
        .collect(joining(", "));
  }

  private static Map<String, CapturedValue> removeReturnFromLocals(
      Map<String, CapturedValue> locals) {
    Map<String, CapturedValue> localMap = new HashMap<>(locals);
    localMap.remove("@return");
    return localMap;
  }
}
