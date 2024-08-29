package datadog.trace.instrumentation.grpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.nio.channels.Channel;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class QueuedCommandInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForKnownTypes {

  private static final String QUEUED_COMMAND = "io.grpc.netty.WriteQueue$QueuedCommand";
  private static final String STATE =
      "datadog.trace.bootstrap.instrumentation.java.concurrent.State";

  public QueuedCommandInstrumentation() {
    super("grpc", "grpc-netty");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(
        isMethod()
            .and(
                named("run")
                    .and(
                        takesArguments(1)
                            .and(takesArgument(0, named("io.netty.channel.Channel"))))),
        getClass().getName() + "$Run");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(QUEUED_COMMAND, STATE);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.grpc.netty.WriteQueue$AbstractQueuedCommand",
      "io.grpc.netty.WriteQueue$RunnableCommand",
      "io.grpc.netty.SendGrpcFrameCommand"
    };
  }

  public static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This Object command) {
      ContextStore<Object, State> contextStore = InstrumentationContext.get(QUEUED_COMMAND, STATE);
      capture(contextStore, command);
      QueueTimerHelper.startQueuingTimer(contextStore, Channel.class, command);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This Object command) {
      return startTaskScope(InstrumentationContext.get(QUEUED_COMMAND, STATE), command);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
