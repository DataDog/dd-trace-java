package datadog.trace.instrumentation.ignite.v2.cache;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractIgniteCacheInstrumentation extends Instrumenter.Tracing {

  public AbstractIgniteCacheInstrumentation() {
    super("ignite");
  }

  public AbstractIgniteCacheInstrumentation(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(
        "org.apache.ignite.internal.processors.cache.IgniteCacheProxy",
        "org.apache.ignite.internal.processors.cache.IgniteCacheProxyImpl",
        "org.apache.ignite.internal.processors.cache.GatewayProtectedCacheProxy",
        "org.apache.ignite.IgniteCache");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.apache.ignite.IgniteCache", "org.apache.ignite.Ignite");
  }
}
