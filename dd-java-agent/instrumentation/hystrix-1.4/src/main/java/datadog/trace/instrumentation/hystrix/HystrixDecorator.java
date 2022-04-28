package datadog.trace.instrumentation.hystrix;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.HYSTRIX_CIRCUIT_OPEN;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.HYSTRIX_COMMAND;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.HYSTRIX_GROUP;

import com.netflix.hystrix.HystrixInvokableInfo;
import datadog.trace.api.Config;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class HystrixDecorator extends BaseDecorator {
  public static HystrixDecorator DECORATE = new HystrixDecorator();

  private final boolean extraTags;
  private final boolean measured;

  private HystrixDecorator(boolean extraTags, boolean measured) {
    this.extraTags = extraTags;
    this.measured = measured;
  }

  private HystrixDecorator() {
    this(Config.get().isHystrixTagsEnabled(), Config.get().isHystrixMeasuredEnabled());
  }

  public static final CharSequence HYSTRIX = UTF8BytesString.create("hystrix");

  private static final DDCache<ResourceNameCacheKey, String> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  private static final Functions.ToString<ResourceNameCacheKey> TO_STRING =
      new Functions.ToString<>();

  private static final class ResourceNameCacheKey {
    private final String group;
    private final String command;
    private final String methodName;

    private ResourceNameCacheKey(String group, String command, String methodName) {
      this.group = group;
      this.command = command;
      this.methodName = methodName;
    }

    public String toString() {
      return group + "." + command + "." + methodName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ResourceNameCacheKey cacheKey = (ResourceNameCacheKey) o;
      return group.equals(cacheKey.group)
          && command.equals(cacheKey.command)
          && methodName.equals(cacheKey.methodName);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + group.hashCode();
      hash = 31 * hash + command.hashCode();
      hash = 31 * hash + methodName.hashCode();
      return hash;
    }
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[0];
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return HYSTRIX;
  }

  public void onCommand(
      final AgentSpan span, final HystrixInvokableInfo<?> command, final String methodName) {
    if (command != null) {
      if (extraTags) {
        span.setTag(HYSTRIX_COMMAND, command.getCommandKey().name());
        span.setTag(HYSTRIX_GROUP, command.getCommandGroup().name());
        span.setTag(HYSTRIX_CIRCUIT_OPEN, command.isCircuitBreakerOpen());
      }
      if (measured) {
        span.setMeasured(true);
      }
      span.setResourceName(
          RESOURCE_NAME_CACHE.computeIfAbsent(
              new ResourceNameCacheKey(
                  command.getCommandGroup().name(), command.getCommandKey().name(), methodName),
              TO_STRING));
    }
  }
}
