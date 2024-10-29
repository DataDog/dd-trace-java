package datadog.trace.bootstrap.blocking;

import datadog.appsec.api.blocking.BlockingException;

public class BlockingExceptionHandler {

  public static Throwable rethrowIfBlockingException(final Throwable e) {
    if (e instanceof BlockingException) {
      throw (BlockingException) e;
    }
    return e;
  }
}
