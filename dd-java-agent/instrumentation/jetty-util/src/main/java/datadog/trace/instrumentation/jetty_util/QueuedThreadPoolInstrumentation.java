package datadog.trace.instrumentation.jetty_util;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class QueuedThreadPoolInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public QueuedThreadPoolInstrumentation() {
    super("jetty-concurrent");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.util.thread.QueuedThreadPool";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(namedOneOf("dispatch", "execute"), getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      task = Wrapper.wrap(task);
    }
  }
}
