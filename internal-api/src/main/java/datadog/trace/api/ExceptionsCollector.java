package datadog.trace.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class ExceptionsCollector {

  public static class Holder {
    public static final ExceptionsCollector INSTANCE = new ExceptionsCollector();
  }

  public static ExceptionsCollector get() {
    return Holder.INSTANCE;
  }

  protected static class Exception {
    // TODO: determine if exceptionString is ok as a unique exception identifier? 
    public String exceptionString; 
    public String stackTrace;

    // SHOULD I BE STORING EXCEPTION IN KEY VALUE INSTEAD?
    // public Exception exception;
  }

  private static final Queue<Exception> exceptions = new LinkedBlockingQueue<>();

  public synchronized void addException(Throwable e) {
    Exception i = new Exception();
    i.exceptionString = e.getMessage();
    i.stackTrace = e.getStackTrace().toString();
    exceptions.offer(i);
  }

  public synchronized Map<String, String> drain() {
    if (exceptions.isEmpty()) {
      return Collections.emptyMap();
    }

    Map<String, String> map = new LinkedHashMap<>();

    Exception i;
    while ((i = exceptions.poll()) != null) {
        map.put(i.exceptionString, i.stackTrace);
    }

    return map;
  }
}

