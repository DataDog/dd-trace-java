package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.cache.RadixTreeCache.PORTS;
import static datadog.trace.api.cache.RadixTreeCache.UNSET_PORT;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public abstract class BaseDecorator {

  private static final QualifiedClassNameCache CLASS_NAMES =
      new QualifiedClassNameCache(
          new Function<Class<?>, CharSequence>() {
            @Override
            public String apply(Class<?> clazz) {
              String simpleName = clazz.getSimpleName();
              if (simpleName.isEmpty()) {
                String name = clazz.getName();
                int start = name.lastIndexOf('.');
                return name.substring(start + 1);
              }
              return simpleName;
            }
          },
          Functions.PrefixJoin.of("."));

  protected final boolean traceAnalyticsEnabled;
  protected final Double traceAnalyticsSampleRate;

  protected BaseDecorator() {
    final Config config = Config.get();
    final String[] instrumentationNames = instrumentationNames();
    this.traceAnalyticsEnabled =
        instrumentationNames.length > 0
            && config.isTraceAnalyticsIntegrationEnabled(
                traceAnalyticsDefault(), instrumentationNames);
    this.traceAnalyticsSampleRate =
        (double) config.getInstrumentationAnalyticsSampleRate(instrumentationNames);
  }

  protected abstract String[] instrumentationNames();

  protected abstract CharSequence spanType();

  protected abstract CharSequence component();

  protected boolean traceAnalyticsDefault() {
    return false;
  }

  public AgentSpan afterStart(final AgentSpan span) {
    if (spanType() != null) {
      span.setSpanType(spanType());
    }
    span.setTag(Tags.COMPONENT, component());
    if (traceAnalyticsEnabled) {
      span.setMetric(DDTags.ANALYTICS_SAMPLE_RATE, traceAnalyticsSampleRate);
    }
    return span;
  }

  public AgentScope beforeFinish(final AgentScope scope) {
    beforeFinish(scope.span());
    return scope;
  }

  public AgentSpan beforeFinish(final AgentSpan span) {
    return span;
  }

  public AgentScope onError(final AgentScope scope, final Throwable throwable) {
    onError(scope.span(), throwable);
    return scope;
  }

  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable != null) {
      span.addThrowable(throwable instanceof ExecutionException ? throwable.getCause() : throwable);
    }
    return span;
  }

  public AgentSpan onPeerConnection(
      final AgentSpan span, final InetSocketAddress remoteConnection) {
    if (remoteConnection != null) {
      onPeerConnection(span, remoteConnection.getAddress(), !remoteConnection.isUnresolved());
      setPeerPort(span, remoteConnection.getPort());
    }
    return span;
  }

  public AgentSpan onPeerConnection(final AgentSpan span, final InetAddress remoteAddress) {
    return onPeerConnection(span, remoteAddress, true);
  }

  public AgentSpan onPeerConnection(AgentSpan span, InetAddress remoteAddress, boolean resolved) {
    if (remoteAddress != null) {
      if (resolved) {
        span.setTag(Tags.PEER_HOSTNAME, remoteAddress.getHostName());
      }
      if (remoteAddress instanceof Inet4Address) {
        span.setTag(Tags.PEER_HOST_IPV4, remoteAddress.getHostAddress());
      } else if (remoteAddress instanceof Inet6Address) {
        span.setTag(Tags.PEER_HOST_IPV6, remoteAddress.getHostAddress());
      }
    }
    return span;
  }

  public AgentSpan setPeerPort(AgentSpan span, String port) {
    span.setTag(Tags.PEER_PORT, port);

    return span;
  }

  public AgentSpan setPeerPort(AgentSpan span, int port) {
    if (port > UNSET_PORT) {
      span.setTag(Tags.PEER_PORT, PORTS.get(port));
    }

    return span;
  }

  /**
   * This method is used to generate an acceptable span (operation) name based on a given method
   * reference. Anonymous classes are named based on their parent.
   *
   * @param method
   * @return
   */
  public CharSequence spanNameForMethod(final Method method) {
    return spanNameForMethod(method.getDeclaringClass(), method);
  }

  /**
   * This method is used to generate an acceptable span (operation) name based on a given method
   * reference. Anonymous classes are named based on their parent.
   *
   * @param method the method to get the name from, nullable
   * @return the span name from the class and method
   */
  public CharSequence spanNameForMethod(final Class<?> clazz, final Method method) {
    if (null == method) {
      return CLASS_NAMES.getClassName(clazz);
    }
    return CLASS_NAMES.getQualifiedName(clazz, method.getName());
  }

  /**
   * This method is used to generate an acceptable span (operation) name based on a given method
   * reference. Anonymous classes are named based on their parent.
   *
   * @param methodName the name of the method to get the name from, nullable
   * @return the span name from the class and method
   */
  public CharSequence spanNameForMethod(final Class<?> clazz, final String methodName) {
    return CLASS_NAMES.getQualifiedName(clazz, methodName);
  }

  /**
   * This method is used to generate an acceptable span (operation) name based on a given class
   * reference. Anonymous classes are named based on their parent.
   *
   * @param clazz
   * @return
   */
  public CharSequence className(final Class<?> clazz) {
    String simpleName = clazz.getSimpleName();
    return simpleName.isEmpty() ? CLASS_NAMES.getClassName(clazz) : simpleName;
  }
}
