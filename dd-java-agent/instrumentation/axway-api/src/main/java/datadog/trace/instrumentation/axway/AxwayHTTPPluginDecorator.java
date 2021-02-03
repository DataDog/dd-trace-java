package datadog.trace.instrumentation.axway;

import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import org.slf4j.Logger;

// request == is com.vordel.circuit.net.State,  response == com.vordel.dwe.http.ServerTransaction
public class AxwayHTTPPluginDecorator extends HttpClientDecorator<Object, Object> {
  public static final AxwayHTTPPluginDecorator DECORATE = new AxwayHTTPPluginDecorator();
  public static final Logger log =
      org.slf4j.LoggerFactory.getLogger(AxwayHTTPPluginDecorator.class);

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"axway-api"};
  }

  @Override
  protected String component() {
    return "axway-api";
  }

  @Override
  protected String method(final Object serverTransaction) {
    try {
      Method m = serverTransaction.getClass().getDeclaredMethod("getMethod");
      m.setAccessible(true);
      return (String) m.invoke(serverTransaction);
    } catch (IllegalAccessException
        | InvocationTargetException
        | NoSuchMethodException
        | ClassCastException e) {
      log.debug("Can't find method 'getMethod' in instance of '" + serverTransaction + "'", e);
    }
    return "GET";
  }

  @Override
  protected URI url(final Object axwayTransactionState) {
    try {
      Field f = axwayTransactionState.getClass().getDeclaredField("uri");
      f.setAccessible(true);
      URI uri = (URI) f.get(axwayTransactionState);
      return uri;
    } catch (NoSuchFieldException | IllegalAccessException | ClassCastException e) {
      log.debug("Can't find field 'uri' in instance: " + axwayTransactionState, e);
    }
    return null;
  }

  @Override
  protected int status(final Object serverTransaction) {
    // done manually
    return 0;
  }
}
