package datadog.trace.instrumentation.axway;

import static java.lang.invoke.MethodType.methodType;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.DefaultURIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;

// request = is com.vordel.circuit.net.State,  connection = com.vordel.dwe.http.ServerTransaction
@Slf4j
public class AxwayHTTPPluginDecorator extends HttpServerDecorator<Object, Object, Object> {
  public static final String HOST = "host";
  public static final String PORT = "port";
  public static final CharSequence AXWAY_REQUEST = UTF8BytesString.createConstant("axway.request");
  public static final CharSequence AXWAY_TRY_TRAMSACTION =
      UTF8BytesString.createConstant("axway.trytransaction");

  public static final AxwayHTTPPluginDecorator DECORATE = new AxwayHTTPPluginDecorator();

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
  private static MethodHandle getRemoteAddr_mh;
  private static MethodHandle getMethod_mh;
  private static MethodHandle getURI_mh; // private

  static {
    try {
      Class<?> classServerTransaction = Class.forName("com.vordel.dwe.http.ServerTransaction");
      getRemoteAddr_mh =
          lookup.findVirtual(
              classServerTransaction, "getRemoteAddr", methodType(InetSocketAddress.class));
      getMethod_mh =
          lookup.findVirtual(classServerTransaction, "getMethod", methodType(String.class));

      Method m = classServerTransaction.getDeclaredMethod("getURI");
      m.setAccessible(true);
      getURI_mh = lookup.unreflect(m);
    } catch (Throwable e) {
      log.debug("Can't find 'com.vordel.dwe.http.ServerTransaction::getRemoteAddr' method: ", e);
    }
  }

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
      return getMethod_mh.invoke(serverTransaction).toString();
    } catch (Throwable throwable) {
      log.debug("Can't invoke com.vordel.dwe.http.ServerTransaction::getMethod(): ", throwable);
    }
    return "UNKNOWN";
  }

  @Override
  protected URIDataAdapter url(final Object serverTransaction) {
    return new DefaultURIDataAdapter(getUri(serverTransaction));
  }

  @Override
  protected String peerHostIP(Object serverTransaction) {
    return getRemoteAddr(serverTransaction).getHostString();
  }

  @Override
  protected int peerPort(Object serverTransaction) {
    return getRemoteAddr(serverTransaction).getPort();
  }

  @Override
  protected int status(final Object serverTransaction) {
    // TODO will be done manually
    return 0;
  }

  public static URI getUri(Object obj) {
    try {
      return (URI) getURI_mh.invoke(obj);
    } catch (Throwable e) {
      log.debug("Can't find invoke 'getUri' in object " + obj, e);
    }
    return URI.create("");
  }

  private static InetSocketAddress getRemoteAddr(Object obj) {
    try {
      return (InetSocketAddress) getRemoteAddr_mh.invoke(obj);
    } catch (Throwable throwable) {
      log.debug("Can't com.vordel.dwe.http.ServerTransaction::getRemoteAddr() : ", throwable);
    }
    return new InetSocketAddress(0);
  }

  public static void setTagFromField(AgentSpan span, String tag, Object obj, String field) {
    span.setTag(tag, getFieldValue(obj, field).toString());
  }

  public static Object getFieldValue(Object obj, String fieldName) {
    try {
      Field field = obj.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      Object v = field.get(obj);
      log.debug("field '{}': {}", fieldName, v);
      return v;
    } catch (NoSuchFieldException | IllegalAccessException e) {
      log.debug("Can't find field '" + fieldName + "': ", e);
    }
    return "null";
  }
}
