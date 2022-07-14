package com.datadog.debugger.util;

import static java.util.stream.Collectors.*;

import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.Snapshot.CapturedContext;
import datadog.trace.bootstrap.debugger.Snapshot.CapturedValue;
import datadog.trace.bootstrap.debugger.Snapshot.ProbeLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/** Helper class for generating snapshot summaries */
public class SnapshotSummary {

  public static String formatMessage(Snapshot snapshot) {
    StringBuilder sb = new StringBuilder();
    sb.append(formatMethod(snapshot));
    sb.append("(");
    sb.append(getArguments(snapshot).map(SnapshotSummary::formatCapturedValues).orElse(""));
    sb.append(")");
    getReturnValue(snapshot).ifPresent(returnValue -> sb.append(": ").append(returnValue));
    getLocalVars(snapshot)
        .map(SnapshotSummary::formatCapturedValues)
        .filter(value -> !value.isEmpty())
        .ifPresent(value -> sb.append("\n").append(value));
    return sb.toString();
  }

  private static Optional<Map<String, CapturedValue>> getLocalVars(Snapshot snapshot) {
    return getLastCapture(snapshot).map(CapturedContext::getLocals);
  }

  private static String formatMethod(Snapshot snapshot) {
    List<CapturedStackFrame> stack = snapshot.getStack();
    ProbeLocation probeLocation =
        snapshot.getProbe() != null ? snapshot.getProbe().getLocation() : null;

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

  private static Optional<CapturedContext> getFirstCapture(Snapshot snapshot) {
    if (snapshot.getCaptures().getEntry() != null) {
      return Optional.of(snapshot.getCaptures().getEntry());
    } else if (snapshot.getCaptures().getLines() != null) {
      Optional<Integer> maybeMin =
          snapshot.getCaptures().getLines().keySet().stream().min(Comparator.naturalOrder());
      return maybeMin.map(firstLine -> snapshot.getCaptures().getLines().get(firstLine));
    } else if (snapshot.getCaptures().getReturn() != null) {
      return Optional.of(snapshot.getCaptures().getReturn());
    }
    return Optional.empty();
  }

  private static Optional<CapturedContext> getLastCapture(Snapshot snapshot) {
    if (snapshot.getCaptures().getReturn() != null) {
      return Optional.of(snapshot.getCaptures().getReturn());
    } else if (snapshot.getCaptures().getLines() != null) {
      Optional<Integer> maybeMax =
          snapshot.getCaptures().getLines().keySet().stream().max(Comparator.naturalOrder());
      return maybeMax.map(firstLine -> snapshot.getCaptures().getLines().get(firstLine));
    } else if (snapshot.getCaptures().getEntry() != null) {
      return Optional.of(snapshot.getCaptures().getEntry());
    }
    return Optional.empty();
  }

  private static Optional<Map<String, CapturedValue>> getArguments(Snapshot snapshot) {
    return getFirstCapture(snapshot).map(CapturedContext::getArguments);
  }

  private static Optional<String> getReturnValue(Snapshot snapshot) {
    CapturedContext exit = snapshot.getCaptures().getReturn();
    if (exit != null && exit.getLocals() != null && exit.getLocals().get("@return") != null) {
      return Optional.of(exit.getLocals().get("@return").getValue());
    }
    return Optional.empty();
  }
}
