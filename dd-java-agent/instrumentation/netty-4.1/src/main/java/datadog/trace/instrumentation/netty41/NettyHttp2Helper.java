package datadog.trace.instrumentation.netty41;

import io.netty.channel.ChannelHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

public class NettyHttp2Helper {
  private static Class HTTP2_CODEC_CLS;
  private static MethodHandle IS_SERVER_FIELD;

  static {
    try {
      HTTP2_CODEC_CLS =
          Class.forName(
              "io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec",
              false,
              NettyHttp2Helper.class.getClassLoader());
      Field f = HTTP2_CODEC_CLS.getDeclaredField("isServer");
      f.setAccessible(true);
      IS_SERVER_FIELD = MethodHandles.lookup().unreflectGetter(f);
    } catch (Throwable t) {
      HTTP2_CODEC_CLS = null;
      IS_SERVER_FIELD = null;
    }
  }

  public static boolean isHttp2FrameCodec(final ChannelHandler handler) {
    return HTTP2_CODEC_CLS != null && HTTP2_CODEC_CLS.isInstance(handler);
  }

  public static boolean isServer(final Object handler) {
    try {
      return IS_SERVER_FIELD != null && (Boolean) IS_SERVER_FIELD.bindTo(handler).invoke();
    } catch (Throwable t) {
      return false;
    }
  }
}
