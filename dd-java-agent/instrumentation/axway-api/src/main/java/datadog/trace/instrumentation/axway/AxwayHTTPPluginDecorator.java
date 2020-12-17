package datadog.trace.instrumentation.axway;

import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.lang.reflect.Field;
import java.net.URI;

// request == is com.vordel.circuit.net.State,  response == com.vordel.dwe.http.ServerTransaction
public class AxwayHTTPPluginDecorator extends HttpClientDecorator<Object, Object> {
  public static final String CORRELATION_HOST = "CORRELATION_HOST";
  public static final String CORRELATION_PORT = "CORRELATION_PORT";
  public static final String AXWAY_REQUEST = "axway.request";
  public static final String AXWAY_TRY_TRANSACTION = "axway.trytransaction";

  public static final AxwayHTTPPluginDecorator DECORATE = new AxwayHTTPPluginDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"axway-api"};
  }

  @Override
  protected String component() {
    return "axway-api";
  }

  @Override
  protected String method(final Object state) {
    try {
      Field f = state.getClass().getDeclaredField("verb");
      f.setAccessible(true);
      return (String) f.get(state);
    } catch (IllegalAccessException | NoSuchFieldException | ClassCastException e) {
      System.out.println("AxwayHTTPPluginDecorator: " + e);
      e.printStackTrace();
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
      System.out.println("AxwayHTTPPluginDecorator: " + e);
      e.printStackTrace();
    }
    return null;
  }

  @Override
  protected int status(final Object clientResponse) {

    return 0;
  }
}
