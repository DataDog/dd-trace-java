package datadog.trace.instrumentation.netty41;

import io.netty.channel.ChannelHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

public class NettyHttp2Helper {
  private static Class HTTP2_CODEC_CLS;
  private static MethodHandle IS_SERVER_FIELD;

  static {
    Class codecClass;
    MethodHandle isServerField;
    try {
      codecClass =
          Class.forName(
              "io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec",
              false,
              NettyHttp2Helper.class.getClassLoader());
      Field f = codecClass.getDeclaredField("isServer");
      f.setAccessible(true);
      isServerField = MethodHandles.lookup().unreflectGetter(f);
    } catch (Throwable t) {
      codecClass = null;
      isServerField = null;
    }
    HTTP2_CODEC_CLS = codecClass;
    IS_SERVER_FIELD = isServerField;
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
