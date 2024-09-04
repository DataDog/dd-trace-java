package mule4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Caused by: java.lang.NullPointerException at java.util.HashMap.putMapEntries(HashMap.java:502) at
 * java.util.HashMap.putAll(HashMap.java:786) at
 * java.util.Collections$SynchronizedMap.putAll(Collections.java:2596) at
 * ch.qos.logback.classic.util.LogbackMDCAdapter.setContextMap(LogbackMDCAdapter.java:197) at
 * org.slf4j.MDC.setContextMap(MDC.java:264) at
 * org.mule.service.http.impl.service.util.ThreadContext.close(ThreadContext.java:61)
 */
@AutoService(InstrumenterModule.class)
public class BuggedMulePatchInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public BuggedMulePatchInstrumentation() {
    super("mule4-test");
  }

  @Override
  public String instrumentedType() {
    return "org.mule.service.http.impl.service.util.ThreadContext";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("close"), getClass().getName() + "$PatchMdcAdvice");
  }

  public static class PatchMdcAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.FieldValue(value = "outerMDC", readOnly = false) Map map) {
      if (map == null) {
        map = new HashMap<>();
      }
    }
  }
}
