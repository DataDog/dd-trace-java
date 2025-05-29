package datadog.trace.instrumentation.zio.v2_0;

import datadog.trace.bootstrap.ContextStore;
import scala.Option;
import zio.Exit;
import zio.Fiber;
import zio.Supervisor;
import zio.Unsafe;
import zio.ZEnvironment;
import zio.ZIO;
import zio.ZIO$;

@SuppressWarnings("unchecked")
public final class TracingSupervisor extends Supervisor<Object> {

  @SuppressWarnings("rawtypes")
  private final ContextStore<Fiber.Runtime, FiberContext> contextStore;

  @SuppressWarnings("rawtypes")
  public TracingSupervisor(ContextStore<Fiber.Runtime, FiberContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public ZIO value(Object trace) {
    return ZIO$.MODULE$.unit();
  }

  @Override
  public <R, E, A_> void onStart(
      ZEnvironment<R> environment,
      ZIO<R, E, A_> effect,
      Option<Fiber.Runtime<Object, Object>> parent,
      Fiber.Runtime<E, A_> fiber,
      Unsafe unsafe) {
    FiberContext context = new FiberContext();
    contextStore.put(fiber, context);
  }

  @Override
  public <R, E, A_> void onEnd(Exit<E, A_> value, Fiber.Runtime<E, A_> fiber, Unsafe unsafe) {
    FiberContext context = contextStore.get(fiber);
    if (context != null) context.onEnd();
  }

  @Override
  public <E, A_> void onSuspend(Fiber.Runtime<E, A_> fiber, Unsafe unsafe) {
    FiberContext context = contextStore.get(fiber);
    if (context != null) context.onSuspend();
  }

  @Override
  public <E, A_> void onResume(Fiber.Runtime<E, A_> fiber, Unsafe unsafe) {
    FiberContext context = contextStore.get(fiber);
    if (context != null) context.onResume();
  }
}
