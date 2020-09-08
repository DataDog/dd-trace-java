package datadog.trace.bootstrap.instrumentation.decorator;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Function;
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

  protected final boolean endToEndDurationsEnabled;
  protected final boolean traceAnalyticsEnabled;
  protected final float traceAnalyticsSampleRate;

  protected BaseDecorator() {
    final Config config = Config.get();
    final String[] instrumentationNames = instrumentationNames();
    this.traceAnalyticsEnabled =
        instrumentationNames.length > 0
            && config.isTraceAnalyticsIntegrationEnabled(
                traceAnalyticsDefault(), instrumentationNames);
    this.traceAnalyticsSampleRate =
        config.getInstrumentationAnalyticsSampleRate(instrumentationNames);
    this.endToEndDurationsEnabled =
        instrumentationNames.length > 0
            && config.isEndToEndDurationEnabled(endToEndDurationsDefault(), instrumentationNames);
  }

  protected abstract String[] instrumentationNames();

  protected abstract String spanType();

  protected abstract String component();

  protected boolean traceAnalyticsDefault() {
    return false;
  }

  protected boolean endToEndDurationsDefault() {
    return false;
  }

  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    if (spanType() != null) {
      span.setTag(DDTags.SPAN_TYPE, spanType());
    }
    span.setTag(Tags.COMPONENT, component());
    if (traceAnalyticsEnabled) {
      span.setTag(DDTags.ANALYTICS_SAMPLE_RATE, traceAnalyticsSampleRate);
    }
    if (endToEndDurationsEnabled) {
      if (null == span.getBaggageItem(DDTags.TRACE_START_TIME)) {
        span.setBaggageItem(
            DDTags.TRACE_START_TIME, Long.toString(NANOSECONDS.toMillis(span.getStartTime())));
      }
    }
    return span;
  }

  public AgentScope beforeFinish(final AgentScope scope) {
    assert scope != null;
    beforeFinish(scope.span());
    return scope;
  }

  public AgentSpan beforeFinish(final AgentSpan span) {
    assert span != null;
    return span;
  }

  public AgentScope onError(final AgentScope scope, final Throwable throwable) {
    assert scope != null;
    onError(scope.span(), throwable);
    return scope;
  }

  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    assert span != null;
    if (throwable != null) {
      span.setError(true);
      span.addThrowable(throwable instanceof ExecutionException ? throwable.getCause() : throwable);
    }
    return span;
  }

  public AgentSpan onPeerConnection(
      final AgentSpan span, final InetSocketAddress remoteConnection) {
    assert span != null;
    if (remoteConnection != null) {
      onPeerConnection(span, remoteConnection.getAddress());

      span.setTag(Tags.PEER_HOSTNAME, remoteConnection.getHostName());
      span.setTag(Tags.PEER_PORT, remoteConnection.getPort());
    }
    return span;
  }

  public AgentSpan onPeerConnection(final AgentSpan span, final InetAddress remoteAddress) {
    assert span != null;
    if (remoteAddress != null) {
      span.setTag(Tags.PEER_HOSTNAME, remoteAddress.getHostName());
      if (remoteAddress instanceof Inet4Address) {
        span.setTag(Tags.PEER_HOST_IPV4, remoteAddress.getHostAddress());
      } else if (remoteAddress instanceof Inet6Address) {
        span.setTag(Tags.PEER_HOST_IPV6, remoteAddress.getHostAddress());
      }
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
  public CharSequence spanNameForClass(final Class<?> clazz) {
    String simpleName = clazz.getSimpleName();
    return simpleName.isEmpty() ? CLASS_NAMES.getClassName(clazz) : simpleName;
  }
}
