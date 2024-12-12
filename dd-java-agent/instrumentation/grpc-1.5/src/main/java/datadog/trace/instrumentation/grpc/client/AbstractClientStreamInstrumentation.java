package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.internal.ClientStreamListener;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class AbstractClientStreamInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForSingleType {

  public AbstractClientStreamInstrumentation() {
    super("grpc", "grpc-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.grpc.internal.ClientStreamListener", AgentSpan.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.grpc.internal.AbstractClientStream";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("start")
            .and(
                isMethod()
                    .and(
                        takesArgument(0, named("io.grpc.internal.ClientStreamListener"))
                            .and(takesArguments(1)))),
        getClass().getName() + "$ActivateSpan");
  }

  public static final class ActivateSpan {
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.Argument(0) ClientStreamListener listener) {
      AgentSpan span =
          InstrumentationContext.get(ClientStreamListener.class, AgentSpan.class).get(listener);
      if (null != span) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }
}
