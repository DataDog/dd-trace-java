package datadog.trace.instrumentation.java.security;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.google.auto.service.AutoService;
import datadog.trace.api.Config;
import datadog.trace.api.iast.InvokeDynamicHelper;
import datadog.trace.api.iast.InvokeDynamicHelperContainer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.Locale;
import javax.annotation.Nullable;

@AutoService(InvokeDynamicHelperContainer.class)
public class WeakHashInstrumentationHelperContainer implements InvokeDynamicHelperContainer {

  @InvokeDynamicHelper
  public static void onHashingAlgorithm(@Nullable final String algorithm) {
    if (algorithm == null) {
      return;
    }
    final IastRequestContext context = IastRequestContext.get();
    if (context == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!Config.get().getIastWeakHashAlgorithms().contains(algorithmId)) {
      return;
    }
    final OverheadController overheadController = context.getOverheadController();
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    // get StackTraceElement for the caller of MessageDigest
    StackTraceElement stackTraceElement = currentStack();

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_HASH,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(algorithm));
    context.getReporter().report(span, vulnerability);
  }

  private static StackTraceElement currentStack() {
    return StackWalkerFactory.INSTANCE.walk(stack -> stack.findFirst().get());
  }
}
