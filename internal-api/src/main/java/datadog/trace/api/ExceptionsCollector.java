package datadog.trace.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.io.PrintWriter;
import java.io.StringWriter;

public class ExceptionsCollector {

  public static class Holder {
    public static final ExceptionsCollector INSTANCE = new ExceptionsCollector();
  }

  public static ExceptionsCollector get() {
    return Holder.INSTANCE;
  }

  protected static class Exception {
    // TODO: is exceptionString is ok as a unique exception identifier? It will be used as key in the map when drained
    public String exceptionString; 
    public String stackTrace;
  }

  private static final Queue<Exception> exceptions = new LinkedBlockingQueue<>();

  public synchronized void addException(Throwable e) {
    Exception i = new Exception();
    i.exceptionString = e.getMessage();

    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    e.printStackTrace(pw);
    String stackTrace = sw.toString();
    i.stackTrace = stackTrace;

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

