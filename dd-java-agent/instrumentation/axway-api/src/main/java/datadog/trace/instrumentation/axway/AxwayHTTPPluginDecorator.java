package datadog.trace.instrumentation.axway;

import datadog.trace.bootstrap.instrumentation.api.DefaultURIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import org.slf4j.Logger;

// request = is com.vordel.circuit.net.State,  connection = com.vordel.dwe.http.ServerTransaction
public class AxwayHTTPPluginDecorator extends HttpServerDecorator<Object, Object, Object> {
  public static final String HOST = "host";
  public static final String PORT = "port";
  public static final String METHOD = "method";
  public static final CharSequence AXWAY_REQUEST = UTF8BytesString.createConstant("axway.request");
  public static final Logger log =
      org.slf4j.LoggerFactory.getLogger(AxwayHTTPPluginDecorator.class);

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
  protected String method(final Object serverTransaction) {
    return invokeNoArgDeclaredMethod(serverTransaction, "getMethod").toString();
  }

  @Override
  protected URIDataAdapter url(final Object serverTransaction) {
    return new DefaultURIDataAdapter((URI) invokeNoArgDeclaredMethod(serverTransaction, "getURI"));
  }

  @Override
  protected String peerHostIP(Object serverTransaction) {
    return ((InetSocketAddress)
            invokeNoArgsSuperSuperClassMethod(serverTransaction, "getLocalAddr"))
        .getHostString();
  }

  @Override
  protected int peerPort(Object serverTransaction) {
    return ((InetSocketAddress)
            invokeNoArgsSuperSuperClassMethod(serverTransaction, "getLocalAddr"))
        .getPort();
  }

  @Override
  protected int status(final Object serverTransaction) {
    // done manually
    return 0;
  }

  public static Object invokeNoArgDeclaredMethod(Object obj, String methodName) {
    try {
      Method m = obj.getClass().getDeclaredMethod(methodName);
      m.setAccessible(true);
      Object v = m.invoke(obj);
      log.debug("{}(): {}", methodName, v);
      return v;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      log.debug("Can't find method '" + methodName + "' in object " + obj, e);
    }
    return "";
  }

  public static Object invokeNoArgsSuperSuperClassMethod(Object obj, String methodName) {
    try {
      Method m = obj.getClass().getSuperclass().getSuperclass().getMethod(methodName);
      Object v = m.invoke(obj);
      log.debug("{}(): {}", methodName, v);
      return v;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      log.debug("Can't find method '" + methodName + "' in object " + obj, e);
    }
    return "";
  }
}
