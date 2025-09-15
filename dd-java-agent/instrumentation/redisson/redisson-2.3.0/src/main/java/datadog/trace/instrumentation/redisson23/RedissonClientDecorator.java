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
    if (RedisCommandRaw&& args.length>0){
      StringBuilder sb = new StringBuilder();
      for (Object val : args) {
        if (val instanceof ByteBuf) {
          ByteBuf buf = (ByteBuf) val;
          if (buf.hasArray()) {
            String data = new String(buf.array(), buf.arrayOffset() + buf.readerIndex(), buf.readableBytes(), StandardCharsets.UTF_8);
            sb.append(data).append(" ");
          } else {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            try{
              byte[] result = new byte[bytes.length - 1];
              System.arraycopy(bytes, 1, result, 0, bytes.length - 2);
              result[result.length - 1] = (byte) (bytes[bytes.length - 1] & 0x7F);
              String data = new String(result, StandardCharsets.UTF_8);
              sb.append(data).append(" ");
            }catch (Exception e){
              System.out.println(e);
            }
          }
        } else {
          sb.append(val.toString()).append(" ");
        }
      }
      span.setTag("redis.command.args",sb.toString());
    }

    return span;
  }
}
