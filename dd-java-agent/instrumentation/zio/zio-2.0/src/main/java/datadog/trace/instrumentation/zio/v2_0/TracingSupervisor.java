package datadog.trace.instrumentation.zio.v2_0;

import static datadog.context.Context.current;
import static datadog.context.Context.empty;
import static datadog.context.Context.from;

import datadog.context.Context;
import scala.Option;
import zio.Exit;
import zio.Fiber;
import zio.Supervisor;
import zio.Unsafe;
import zio.ZEnvironment;
import zio.ZIO;
import zio.ZIO$;

public final class TracingSupervisor extends Supervisor<Object> {
  @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
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
    // Store current context to new fiber
    current().attachTo(fiber);
  }

  @Override
  public <R, E, A_> void onEnd(Exit<E, A_> value, Fiber.Runtime<E, A_> fiber, Unsafe unsafe) {}

  @Override
  public <E, A_> void onSuspend(Fiber.Runtime<E, A_> fiber, Unsafe unsafe) {
    // Store current context on fiber deactivation
    current().attachTo(fiber);
    // Clear context to avoid context leak
    empty().makeCurrent();
  }

  @Override
  public <E, A_> void onResume(Fiber.Runtime<E, A_> fiber, Unsafe unsafe) {
    // Restore stored context on fiber activation
    Context context = from(fiber);
    if (context != null) {
      context.makeCurrent();
    }
  }
}
