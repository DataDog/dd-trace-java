package datadog.trace.instrumentation.axway;

import static java.lang.invoke.MethodType.methodType;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// request = is com.vordel.circuit.net.State,  connection = com.vordel.dwe.http.ServerTransaction
public class AxwayHTTPPluginDecorator extends HttpServerDecorator<Object, Object, Object, Void> {
  private static final Logger log = LoggerFactory.getLogger(AxwayHTTPPluginDecorator.class);

  public static final CharSequence AXWAY_TRY_TRANSACTION =
      UTF8BytesString.create("axway.trytransaction");

  public static final AxwayHTTPPluginDecorator DECORATE = new AxwayHTTPPluginDecorator();

  public static final CharSequence AXWAY_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private static final String SERVERTRANSACTION_CLASSNAME = "com.vordel.dwe.http.ServerTransaction";
  private static final Class<?> classServerTransaction;
  private static final MethodHandle getRemoteAddr_mh;
  private static final MethodHandle getMethod_mh;
  private static final MethodHandle getURI_mh;

  private static final String STATE_CLASSNAME = "com.vordel.circuit.net.State";
  private static final Class<?> classState;
  private static final MethodHandle hostField_mh;
  private static final MethodHandle portField_mh;
  private static final MethodHandle methodField_mh;
  private static final MethodHandle uriField_mh;

  static final String SERVER_TRANSACTION_CLASSNAME = "com.vordel.dwe.http.ServerTransaction";
  static final Class<Object> SERVER_TRANSACTION_CLASS = getServerTransactionClass();

  @SuppressForbidden
  private static Class<Object> getServerTransactionClass() {
    try {
      return (Class<Object>) Class.forName(SERVER_TRANSACTION_CLASSNAME);
    } catch (ClassNotFoundException e) {
      log.debug("Can't get ServerTransaction class name", e);
    }
    return null;
  }

  static {
    classServerTransaction = initClass(SERVERTRANSACTION_CLASSNAME);
    getRemoteAddr_mh =
        initNoArgServerTransactionMethodHandle("getRemoteAddr", InetSocketAddress.class);
    getMethod_mh = initNoArgServerTransactionMethodHandle("getMethod", String.class);
    getURI_mh = initGetURI();

    classState = initClass(STATE_CLASSNAME);
    hostField_mh = initStateFieldGetter("host");
    portField_mh = initStateFieldGetter("port");
    methodField_mh = initStateFieldGetter("verb");
    uriField_mh = initStateFieldGetter("uri");
  }

  private static Class<?> initClass(final String name) {
    try {
      return Class.forName(name, false, AxwayHTTPPluginDecorator.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      log.debug(
          "Can't find class '{}': Axaway integration failed. ", SERVERTRANSACTION_CLASSNAME, e);
    }
    return null;
  }

  private static MethodHandle initNoArgServerTransactionMethodHandle(String name, Class<?> rtype) {
    try {
      return lookup.findVirtual(classServerTransaction, name, methodType(rtype));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      log.debug("Can't find method handler '{}' ", name, e);
    }
    return null;
  }

  private static MethodHandle initGetURI() {
    Method m = null;
    try {
      m = classServerTransaction.getDeclaredMethod("getURI"); // private method
      m.setAccessible(true);
      return lookup.unreflect(m);
    } catch (Throwable e) {
      log.debug("Can't unreflect method '{}': ", m, e);
    }
    return null;
  }

  private static MethodHandle initStateFieldGetter(String fieldName) {
    Field field = null;
    MethodHandle mh = null;
    try {
      field = classState.getDeclaredField(fieldName);
      field.setAccessible(true);
      mh = lookup.unreflectGetter(field);
      log.debug(
          "Initialized field '{}' of class '{}' unreflected to {}", fieldName, classState, mh);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      log.debug(
          "Can't find and unreflect declared field '{}' with name '{}' for class '{}' to mh: '{}'",
          field,
          fieldName,
          classState,
          mh,
          e);
    }
    return mh;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"axway-http"};
  }

  @Override
  protected String component() {
    return "axway-http";
  }

  @Override
  protected AgentPropagation.ContextVisitor<Void> getter() {
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Object> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return AXWAY_REQUEST;
  }

  @Override
  protected String method(final Object serverTransaction) {
    try {
      return (String) getMethod_mh.invoke(serverTransaction);
    } catch (Throwable throwable) {
      log.debug(
          "Can't invoke invoke '{}' on instance '{}' of class '{}'",
          getMethod_mh,
          serverTransaction,
          serverTransaction.getClass(),
          throwable);
    }
    return "UNKNOWN";
  }

  @Override
  protected URIDataAdapter url(final Object serverTransaction) {
    try {
      return new URIDefaultDataAdapter((URI) getURI_mh.invoke(serverTransaction));
    } catch (Throwable e) {
      log.debug("Can't find invoke '{}}' on '{}': ", getURI_mh, serverTransaction, e);
    }
    return null;
  }

  @Override
  protected String peerHostIP(Object serverTransaction) {
    return getRemoteAddr(serverTransaction).getHostString();
  }

  @Override
  protected int peerPort(Object serverTransaction) {
    return getRemoteAddr(serverTransaction).getPort();
  }

  /**
   * @param serverTransaction instance of {@value #SERVERTRANSACTION_CLASSNAME}
   */
  @Override
  protected int status(final Object serverTransaction) {
    // TODO will be done manually
    return 0;
  }

  /**
   * @param stateInstance type com.vordel.circuit.net.State
   */
  public AgentSpan onTransaction(AgentSpan span, Object stateInstance) {
    if (span != null) {
      setStringTagFromStateField(span, Tags.PEER_HOSTNAME, stateInstance, hostField_mh);
      setStringTagFromStateField(span, Tags.PEER_PORT, stateInstance, portField_mh);
      setStringTagFromStateField(span, Tags.HTTP_METHOD, stateInstance, methodField_mh);
      setURLTagFromUriStateField(span, stateInstance);
    }
    return span;
  }

  /**
   * class hierarchy in package com.vordel.dwe.http :
   *
   * <pre><code>
   * public class Transaction implements IMetricsTransaction {
   *    public native InetSocketAddress getLocalAddr();
   *    public native InetSocketAddress getRemoteAddr();
   * }
   * public abstract class HTTPTransaction extends Transaction {
   *
   * }
   * public class ServerTransaction extends HTTPTransaction {
   *
   * }
   * </code></pre>
   *
   * @param obj instance of {@value #SERVERTRANSACTION_CLASSNAME}
   * @return result of {@value #SERVERTRANSACTION_CLASSNAME}::getRemoteAddr()
   */
  private static InetSocketAddress getRemoteAddr(Object obj) {
    try {
      return (InetSocketAddress) getRemoteAddr_mh.invoke(obj);
    } catch (Throwable throwable) {
      log.debug("Can't invoke '{}' on instance '{}': ", getRemoteAddr_mh, obj, throwable);
    }
    return new InetSocketAddress(0);
  }

  private static void setStringTagFromStateField(
      AgentSpan span, String tag, Object stateInstance, MethodHandle mh) {
    String v = "";
    try {
      v = (String) mh.invoke(stateInstance);
    } catch (Throwable e) {
      log.debug(
          "Can't invoke '{}' on instance '{}'; ; Tag '{}' not set.", mh, stateInstance, tag, e);
    }
    span.setTag(tag, v);
  }

  private static void setURLTagFromUriStateField(AgentSpan span, Object stateInstance) {
    try {
      span.setTag(Tags.HTTP_URL, uriField_mh.invoke(stateInstance).toString());
    } catch (Throwable e) {
      log.debug(
          "Can't invoke '{}' on instance '{}'; Tag '{}' not set.",
          uriField_mh,
          stateInstance,
          Tags.HTTP_URL,
          e);
    }
  }
}
