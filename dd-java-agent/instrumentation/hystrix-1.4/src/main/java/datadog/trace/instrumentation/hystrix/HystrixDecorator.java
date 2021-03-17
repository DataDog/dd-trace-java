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

  private HystrixDecorator(boolean extraTags) {
    this.extraTags = extraTags;
  }

  private HystrixDecorator() {
    this(Config.get().isHystrixTagsEnabled());
  }

  public static final CharSequence HYSTRIX = UTF8BytesString.create("hystrix");

  private static final DDCache<ResourceNameCacheKey, UTF8BytesString> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  private static final Functions.ToUTF8String<ResourceNameCacheKey> TO_STRING =
      new Functions.ToUTF8String<>();

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
      if (!(o instanceof ResourceNameCacheKey)) return false;
      ResourceNameCacheKey cacheKey = (ResourceNameCacheKey) o;
      return group.equals(cacheKey.group)
          && command.equals(cacheKey.command)
          && methodName.equals(cacheKey.methodName);
    }

    @Override
    public int hashCode() {
      return 961 * group.hashCode() + 31 * command.hashCode() + methodName.hashCode();
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
      span.setResourceName(
          RESOURCE_NAME_CACHE.computeIfAbsent(
              new ResourceNameCacheKey(
                  command.getCommandGroup().name(), command.getCommandKey().name(), methodName),
              TO_STRING));
    }
  }
}
