package datadog.trace.instrumentation.java.concurrent;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.profiling.UnwrappingVisitor;

public class TaskUnwrappingInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasTypeAdvice {

  private static final String[] TYPES_WITH_FIELDS = {
    "java.util.concurrent.FutureTask",
    "callable",
    "java.util.concurrent.Executors$RunnableAdapter",
    "task",
    "java.util.concurrent.CompletableFuture$AsyncSupply",
    "fn",
    "java.util.concurrent.CompletableFuture$AsyncRun",
    "fn",
    "java.util.concurrent.ForkJoinTask$AdaptedRunnable",
    "runnable",
    "java.util.concurrent.ForkJoinTask$AdaptedCallable",
    "callable",
    "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction",
    "runnable",
    "java.util.concurrent.ForkJoinTask$RunnableExecuteAction",
    "runnable",
    "java.util.concurrent.ForkJoinTask$AdaptedInterruptibleCallable",
    "callable",
    // netty
    "io.netty.util.concurrent.PromiseTask$RunnableAdapter",
    "task",
    "io.netty.util.concurrent.PromiseTask",
    "task",
    "io.netty.channel.AbstractChannelHandlerContext$WriteTask",
    "msg",
    // netty shaded into gRPC
    "io.grpc.netty.shaded.io.netty.util.concurrent.PromiseTask$RunnableAdapter",
    "task",
    "io.grpc.netty.shaded.io.netty.util.concurrent.PromiseTask",
    "task",
    "io.grpc.netty.shaded.io.netty.channel.AbstractChannelHandlerContext$WriteTask",
    "msg",
    "io.grpc.Context$1",
    "val$r",
    "io.grpc.Context$2",
    "val$c",
    "io.grpc.netty.WriteQueue$RunnableCommand",
    "runnable",
    "io.grpc.internal.LogExceptionRunnable",
    "task",
    "akka.dispatch.TaskInvocation",
    "runnable",
    "scala.concurrent.impl.CallbackRunnable",
    "onComplete"
  };

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new UnwrappingVisitor(TYPES_WITH_FIELDS));
  }

  @Override
  public String[] knownMatchingTypes() {
    String[] types = new String[TYPES_WITH_FIELDS.length / 2];
    for (int i = 0; i < types.length; i++) {
      types[i] = TYPES_WITH_FIELDS[i * 2];
    }
    return types;
  }
}
