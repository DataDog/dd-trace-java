package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver.hostName;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Functions;
import datadog.trace.api.TagMap;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public abstract class BaseDecorator {
  protected static final int UNSET_PORT = 0;

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
  protected final double traceAnalyticsSampleRate;

  private final TagMap.Entry traceAnalyticsEntry;

  // Deliberately not volatile: reading a stale null and rebuilding is safe. SpanPrototype is
  // frozen, so a benign race produces two equivalent prototypes and either is fine.
  private SpanPrototype cachedSpanPrototype = null;

  protected BaseDecorator() {
    final Config config = Config.get();
    final String[] instrumentationNames = instrumentationNames();

    this.traceAnalyticsEnabled =
        instrumentationNames.length > 0
            && config.isTraceAnalyticsIntegrationEnabled(
                traceAnalyticsDefault(), instrumentationNames);

    this.traceAnalyticsSampleRate =
        (double) config.getInstrumentationAnalyticsSampleRate(instrumentationNames);

    this.traceAnalyticsEntry =
        this.traceAnalyticsEnabled
            ? TagMap.Entry.create(DDTags.ANALYTICS_SAMPLE_RATE, traceAnalyticsSampleRate)
            : null;
  }

  protected abstract String[] instrumentationNames();

  protected abstract CharSequence spanType();

  protected abstract CharSequence component();

  /**
   * The baked-once {@link SpanPrototype} carrying this decorator's constant identity and tags: span
   * type, component, integration name, and — via the {@link ServerDecorator} / {@link
   * ClientDecorator} extensions — span kind and language.
   *
   * <p>Built lazily on first access, not in the constructor: {@link #component()}, {@link
   * #spanType()}, and (in {@link ClientDecorator}) {@code spanKind()} are overridable and may
   * reference statics that are not yet initialized while the decorator singleton is under
   * construction. Deferring the build sidesteps that field-initialization-ordering hazard (the same
   * one the old per-{@link TagMap.Entry} caches guarded against) while collapsing those several
   * caches into a single object. Not volatile: {@link SpanPrototype} is frozen, so a benign race
   * rebuilds an equivalent prototype.
   */
  protected final SpanPrototype spanPrototype() {
    SpanPrototype prototype = cachedSpanPrototype;
    if (prototype == null) {
      cachedSpanPrototype = prototype = buildSpanPrototype();
    }
    return prototype;
  }

  /**
   * Builds this decorator's {@link SpanPrototype}. Subclasses extend the chain with {@link
   * SpanPrototype.Builder#extends_} to add their level's constants (see {@link ServerDecorator} /
   * {@link ClientDecorator}), mirroring the decorator class hierarchy. Called once per decorator,
   * lazily — see {@link #spanPrototype()}.
   */
  protected SpanPrototype buildSpanPrototype() {
    return SpanPrototype.builder()
        .initSpanType(spanType())
        .initComponentAndIntegration(component())
        .build();
  }

  protected boolean traceAnalyticsDefault() {
    return false;
  }

  public void afterStart(final AgentSpan span) {
    // Stamps the prototype's constant span type, tags, and integration name as fallback defaults.
    // apply is the single seam the construction-seeding path shares; because it never clobbers, it
    // self-neutralizes once construction has already seeded the same prototype.
    span.apply(spanPrototype());

    // null handled by setMetric
    span.setMetric(traceAnalyticsEntry);
  }

  public void beforeFinish(final ContextScope scope) {
    beforeFinish(scope.context());
  }

  public void beforeFinish(final AgentSpan span) {}

  public void beforeFinish(final Context context) {}

  public void onError(final AgentScope scope, final Throwable throwable) {
    if (scope != null) {
      onError(scope.span(), throwable);
    }
  }

  public void onError(final AgentSpan span, final Throwable throwable) {
    onError(span, throwable, ErrorPriorities.DEFAULT);
  }

  public void onError(final AgentSpan span, final Throwable throwable, byte errorPriority) {
    if (throwable != null && span != null) {
      span.addThrowable(
          throwable instanceof ExecutionException ? throwable.getCause() : throwable,
          errorPriority);
    }
  }

  public void onError(final ContextScope scope, final Throwable throwable) {
    if (scope != null) {
      onError(AgentSpan.fromContext(scope.context()), throwable);
    }
  }

  public void onPeerConnection(final AgentSpan span, final InetSocketAddress remoteConnection) {
    if (remoteConnection != null) {
      onPeerConnection(span, remoteConnection.getAddress(), !remoteConnection.isUnresolved());
      setPeerPort(span, remoteConnection.getPort());
    }
  }

  public void onPeerConnection(final AgentSpan span, final InetAddress remoteAddress) {
    onPeerConnection(span, remoteAddress, true);
  }

  public void onPeerConnection(AgentSpan span, InetAddress remoteAddress, boolean resolved) {
    if (remoteAddress != null) {
      String ip = remoteAddress.getHostAddress();
      if (resolved && Config.get().isPeerHostNameEnabled()) {
        span.setTag(Tags.PEER_HOSTNAME, hostName(remoteAddress, ip));
      }
      if (remoteAddress instanceof Inet4Address) {
        span.setTag(Tags.PEER_HOST_IPV4, ip);
      } else if (remoteAddress instanceof Inet6Address) {
        span.setTag(Tags.PEER_HOST_IPV6, ip);
      }
    }
  }

  public void setPeerPort(AgentSpan span, String port) {
    span.setTag(Tags.PEER_PORT, port);
  }

  public void setPeerPort(AgentSpan span, int port) {
    if (port > UNSET_PORT) {
      span.setTag(Tags.PEER_PORT, port);
    }
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
