package datadog.trace.instrumentation.redisson23;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import org.redisson.client.protocol.CommandData;

public class RedissonClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<CommandData<?, ?>> {
  public static final RedissonClientDecorator DECORATE = new RedissonClientDecorator();

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service("redis");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");
  public boolean RedisCommandRaw = Config.get().getRedisCommandArgs();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"redisson", "redis"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.REDIS;
  }

  @Override
  protected String dbType() {
    return "redis";
  }

  @Override
  protected String dbUser(CommandData<?, ?> commandData) {
    return null;
  }

  @Override
  protected String dbInstance(CommandData<?, ?> commandData) {
    return null;
  }

  @Override
  protected CharSequence dbHostname(CommandData<?, ?> commandData) {
    return null;
  }

  public AgentSpan onArgs(final AgentSpan span, Object[] args) {
    if (RedisCommandRaw) {
      span.setTag("redis.command.args", getReadableParams(args));
    }
    return span;
  }

  public String getReadableParams(Object[] params) {
    if (params == null) return "[]";

    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < params.length; i++) {
      Object param = params[i];

      if (param instanceof byte[]) {
        // 将字节数组转为 UTF-8 字符串
        sb.append(new String((byte[]) param, java.nio.charset.StandardCharsets.UTF_8));
      } else if (param instanceof io.netty.buffer.ByteBuf) {
        io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) param;
        // 使用 copy() 避免影响原始 Buf 的读写索引
        // 使用 UTF_8 编码（假设你的数据是文本）
        sb.append(buf.toString(java.nio.charset.StandardCharsets.UTF_8));
      } else {
        sb.append(param);
      }

      if (i < params.length - 1) {
        sb.append(", ");
      }
    }
    return sb.append("]").toString();
  }
}
