package datadog.trace.instrumentation.spymemcached;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import net.spy.memcached.MemcachedConnection;

public class MemcacheClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<MemcachedConnection> {
  private static final CharSequence JAVA_SPYMEMCACHED =
      UTF8BytesString.createConstant("java-spymemcached");
  public static final MemcacheClientDecorator DECORATE = new MemcacheClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spymemcached"};
  }

  @Override
  protected String service() {
    return "memcached";
  }

  @Override
  protected CharSequence component() {
    return JAVA_SPYMEMCACHED;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.MEMCACHED;
  }

  @Override
  protected String dbType() {
    return "memcached";
  }

  @Override
  protected String dbUser(final MemcachedConnection session) {
    return null;
  }

  @Override
  protected String dbInstance(final MemcachedConnection connection) {
    return null;
  }

  public AgentSpan onOperation(final AgentSpan span, final String methodName) {

    final char[] chars =
        methodName
            .replaceFirst("^async", "")
            // 'CAS' name is special, we have to lowercase whole name
            .replaceFirst("^CAS", "cas")
            .toCharArray();

    // Lowercase first letter
    chars[0] = Character.toLowerCase(chars[0]);

    span.setTag(DDTags.RESOURCE_NAME, new String(chars));
    return span;
  }
}
