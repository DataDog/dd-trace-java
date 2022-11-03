package datadog.trace.instrumentation.java.lang;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.google.auto.service.AutoService;
import datadog.trace.api.iast.InvokeDynamicHelper;
import datadog.trace.api.iast.InvokeDynamicHelperContainer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@AutoService(InvokeDynamicHelperContainer.class)
public class RuntimeHelperContainer implements InvokeDynamicHelperContainer {

  @InvokeDynamicHelper
  public static void onRuntimeExec(@Nullable final String... cmdArray) {
    if (!canBeTaintedNullSafe(cmdArray)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    checkCommandInjection(taintedObjects, Arrays.asList(cmdArray));
  }

  private static void checkCommandInjection(
      @Nonnull final TaintedObjects taintedObjects, @Nonnull final List<String> command) {
    final Map<String, Range[]> taintedMap = new HashMap<>();
    int rangeCount = fetchRanges(command, taintedObjects, taintedMap);
    if (rangeCount == 0) {
      return;
    }
    final IastRequestContext context = IastRequestContext.get();
    if (context == null) {
      return;
    }
    final OverheadController overheadController = context.getOverheadController();
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final StringBuilder evidence = new StringBuilder();
    final Range[] targetRanges = new Range[rangeCount];
    int rangeIndex = 0;
    for (int i = 0; i < command.size(); i++) {
      if (i > 0) {
        evidence.append(" ");
      }
      final String cmd = command.get(i);
      final Range[] taintedRanges = taintedMap.get(cmd);
      if (taintedRanges != null) {
        Ranges.copyShift(taintedRanges, targetRanges, rangeIndex, evidence.length());
        rangeIndex += taintedRanges.length;
      }
      evidence.append(cmd);
    }
    context
        .getReporter()
        .report(
            span,
            new Vulnerability(
                VulnerabilityType.COMMAND_INJECTION,
                Location.forSpanAndStack(span.getSpanId(), currentStack()),
                new Evidence(evidence.toString(), targetRanges)));
  }

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence[] args) {
    if (args == null || args.length == 0) {
      return false;
    }
    for (final CharSequence item : args) {
      if (canBeTaintedNullSafe(item)) {
        return true;
      }
    }
    return false;
  }

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence s) {
    return s != null && canBeTainted(s);
  }

  private static boolean canBeTainted(@Nonnull final CharSequence s) {
    return s.length() > 0;
  }

  private static <E extends CharSequence> int fetchRanges(
      @Nullable final List<E> items,
      @Nonnull final TaintedObjects to,
      @Nonnull final Map<E, Range[]> map) {
    if (items == null) {
      return 0;
    }
    int result = 0;
    for (final E item : items) {
      final TaintedObject tainted = to.get(item);
      if (tainted != null) {
        final Range[] ranges = tainted.getRanges();
        map.put(item, ranges);
        result += ranges.length;
      }
    }
    return result;
  }

  private static StackTraceElement currentStack() {
    return StackWalkerFactory.INSTANCE.walk(stack -> stack.findFirst().get());
  }
}
