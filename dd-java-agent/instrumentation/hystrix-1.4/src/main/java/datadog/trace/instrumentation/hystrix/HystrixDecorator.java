package datadog.trace.instrumentation.hystrix;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.HYSTRIX_CIRCUIT_OPEN;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.HYSTRIX_COMMAND;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.HYSTRIX_GROUP;

import com.netflix.hystrix.HystrixInvokableInfo;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Functions;
import datadog.trace.bootstrap.instrumentation.cache.DDCache;
import datadog.trace.bootstrap.instrumentation.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.Objects;

public class HystrixDecorator extends BaseDecorator {
  public static HystrixDecorator DECORATE = new HystrixDecorator();

  private final boolean extraTags;

  private HystrixDecorator(boolean extraTags) {
    this.extraTags = extraTags;
  }

  private HystrixDecorator() {
    this(Config.get().isHystrixTagsEnabled());
  }

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
      return Objects.hash(group, command, methodName);
    }
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[0];
  }

  @Override
  protected String spanType() {
    return null;
  }

  @Override
  protected String component() {
    return "hystrix";
  }

  public void onCommand(
      final AgentSpan span, final HystrixInvokableInfo<?> command, final String methodName) {
    if (command != null) {
      if (extraTags) {
        span.setTag(HYSTRIX_COMMAND, command.getCommandKey().name());
        span.setTag(HYSTRIX_GROUP, command.getCommandGroup().name());
        span.setTag(HYSTRIX_CIRCUIT_OPEN, command.isCircuitBreakerOpen());
      }
      span.setTag(
          DDTags.RESOURCE_NAME,
          RESOURCE_NAME_CACHE.computeIfAbsent(
              new ResourceNameCacheKey(
                  command.getCommandGroup().name(), command.getCommandKey().name(), methodName),
              TO_STRING));
    }
  }
}
