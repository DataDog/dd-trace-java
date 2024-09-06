package datadog.trace.instrumentation.redisson30;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletionStage;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redisson has a class RFuture that's implements {@link CompletionStage}. RFuture is available in
 * 3.0.0 but not later. Later versions are directly using {@link
 * java.util.concurrent.CompletableFuture} hence we use method handles that can always cast to
 * {@link CompletionStage} otherwise muzzle blocks the instrumentation since RFuture is missing.
 */
public class PromiseHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(PromiseHelper.class);
  public static final MethodHandle COMMAND_GET_PROMISE_HANDLE =
      resolveMethodHandles(CommandData.class);
  public static final MethodHandle COMMANDS_GET_PROMISE_HANDLE =
      resolveMethodHandles(CommandsData.class);

  private static MethodHandle resolveMethodHandles(Class<?> cls) {
    final MethodHandle ret =
        new MethodHandles(PromiseHelper.class.getClassLoader()).method(cls, "getPromise");
    if (ret == null) {
      LOGGER.debug(
          "Redisson instrumentation blocked. Cannot resolve getPromise method handle for class {}",
          cls);
      return null;
    }
    try {
      return ret.asType(MethodType.methodType(CompletionStage.class, cls));
    } catch (Throwable t) {
      LOGGER.debug(
          "Redisson instrumentation blocked. Cannot getPromise method for class {} is not compatible with CompletionStage",
          cls,
          t);
    }
    return null;
  }

  public static CompletionStage<?> getPromise(MethodHandle mh, Object target) {
    if (mh == null) {
      return null;
    }
    try {
      return (CompletionStage<?>) mh.invoke(target);
    } catch (Throwable t) {
      // can be ignored
    }
    return null;
  }

  private PromiseHelper() {}
}
