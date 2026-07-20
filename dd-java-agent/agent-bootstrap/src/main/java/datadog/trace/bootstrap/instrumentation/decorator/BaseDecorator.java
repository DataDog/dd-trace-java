package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver.hostName;

import datadog.appsec.api.blocking.BlockingException;
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
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for span decorators.
 *
 * <p>Decorator lifecycle hooks such as {@link #onError(AgentSpan, Throwable)} and {@link
 * #beforeFinish(AgentSpan)} are invoked from instrumentation advice, often after the active scope
 * has been closed and around {@code span.finish()}. To keep that advice free of defensive {@code
 * try/finally} blocks, these hooks <strong>must not throw</strong>: the public methods are {@code
 * final} and route through an exception barrier that logs and swallows any {@link Throwable} raised
 * by the overridable {@code doOnError}/{@code doBeforeFinish} hooks. {@link BlockingException} is
 * deliberately re-thrown so AppSec/RASP blocking keeps working. Subclasses customize behavior by
 * overriding the protected {@code doXxx} hooks, which should themselves avoid throwing (other than
 * {@link BlockingException}).
 */
public abstract class BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(BaseDecorator.class);

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

  // Deliberately not volatile, reading null and repeating the calculation is safe
  private TagMap.Entry cachedComponentEntry = null;

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

  /** Caches the component TagMap.Entry, so it isn't recreated for every trace */
  protected final TagMap.Entry componentEntry() {
    // DQH = Tried calling component() in the constructor, but that had issues with static
    // field ordering.  That was caught be an integration test, but I didn't want to risk
    // breaking other integrations where the test is not as thorough.

    // This approach while more complicated doesn't have any field initialization ordering issues.
    TagMap.Entry componentEntry = cachedComponentEntry;
    if (componentEntry == null) {
      cachedComponentEntry = componentEntry = TagMap.Entry.create(Tags.COMPONENT, component());
    }
    return componentEntry;
  }

  protected boolean traceAnalyticsDefault() {
    return false;
  }

  public void afterStart(final AgentSpan span) {
    if (spanType() != null) {
      span.setSpanType(spanType());
    }

    span.setTag(componentEntry());

    // DQH - Could retrieve the value from componentEntry and cast to avoid the virtual call,
    // unclear which option is better here
    final CharSequence component = component();
    span.spanContext().setIntegrationName(component);

    // null handled by setMetric
    span.setMetric(traceAnalyticsEntry);
  }

  public final void beforeFinish(final ContextScope scope) {
    beforeFinish(scope.context());
  }

  public final void beforeFinish(final AgentSpan span) {
    beforeFinish((Context) span);
  }

  public final void beforeFinish(final Context context) {
    try {
      doBeforeFinish(context);
    } catch (BlockingException e) {
      throw e;
    } catch (Throwable t) {
      log.debug("Failed to decorate span before finish", t);
    }
  }

  /**
   * Hook invoked by {@link #beforeFinish(Context)} behind an exception barrier. Override to add
   * decoration before the span is finished. Implementations must not throw (other than {@link
   * BlockingException}).
   */
  protected void doBeforeFinish(final Context context) {}

  public final void onError(final AgentScope scope, final Throwable throwable) {
    if (scope != null) {
      onError(scope.span(), throwable);
    }
  }

  public final void onError(final AgentSpan span, final Throwable throwable) {
    onError(span, throwable, ErrorPriorities.DEFAULT);
  }

  public final void onError(final AgentSpan span, final Throwable throwable, byte errorPriority) {
    try {
      doOnError(span, throwable, errorPriority);
    } catch (BlockingException e) {
      throw e;
    } catch (Throwable t) {
      log.debug("Failed to decorate span on error", t);
    }
  }

  public final void onError(final ContextScope scope, final Throwable throwable) {
    if (scope != null) {
      onError(AgentSpan.fromContext(scope.context()), throwable);
    }
  }

  /**
   * Hook invoked by {@link #onError(AgentSpan, Throwable, byte)} behind an exception barrier.
   * Override to customize error decoration. Implementations must not throw (other than {@link
   * BlockingException}).
   */
  protected void doOnError(final AgentSpan span, final Throwable throwable, byte errorPriority) {
    if (throwable != null && span != null) {
      span.addThrowable(
          throwable instanceof ExecutionException ? throwable.getCause() : throwable,
          errorPriority);
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
