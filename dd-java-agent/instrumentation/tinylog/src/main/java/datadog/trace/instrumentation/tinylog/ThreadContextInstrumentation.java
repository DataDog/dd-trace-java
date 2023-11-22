package datadog.trace.instrumentation.tinylog;

import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.WithGlobalTracer;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ThreadContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  private static final String TYPE_NAME = "org.tinylog.ThreadContext";

  public ThreadContextInstrumentation() {
    super("tinylog");
  }

  @Override
  public String instrumentedType() {
    return TYPE_NAME;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isTypeInitializer(), ThreadContextInstrumentation.class.getName() + "$ThreadContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.LogContextScopeListener",
      "datadog.trace.instrumentation.tinylog.ThreadContextUpdater"
    };
  }

  public static class ThreadContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized() {
      WithGlobalTracer.registerOrExecute(new ThreadContextUpdater());
    }
  }
}
