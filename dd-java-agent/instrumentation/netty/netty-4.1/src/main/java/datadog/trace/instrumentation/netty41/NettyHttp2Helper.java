package datadog.trace.instrumentation.netty41;

import io.netty.channel.ChannelHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyHttp2Helper {
  private static final Class HTTP2_STREAM_FRAME_CODEC_CLS;
  private static final Class HTTP2_CONNECTION_CODEC_CLS;
  private static final MethodHandle IS_SERVER_FIELD;
  private static final Logger LOGGER = LoggerFactory.getLogger(NettyHttp2Helper.class);

  static {
    Class codecClass;
    Class frameCodecClass;
    MethodHandle isServerField;
    try {
      codecClass =
          Class.forName(
              "io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec",
              false,
              NettyHttp2Helper.class.getClassLoader());
      Field f = codecClass.getDeclaredField("isServer");
      f.setAccessible(true);
      isServerField =
          MethodHandles.lookup()
              .unreflectGetter(f)
              .asType(MethodType.methodType(boolean.class, ChannelHandler.class));
    } catch (final ClassNotFoundException cnfe) {
      // can be expected
      codecClass = null;
      isServerField = null;
    } catch (Throwable t) {
      // unexpected
      codecClass = null;
      isServerField = null;
      LOGGER.debug("Unable to setup netty http2 instrumentation", t);
    }
    try {
      frameCodecClass =
          Class.forName(
              "io.netty.handler.codec.http2.Http2FrameCodec",
              false,
              NettyHttp2Helper.class.getClassLoader());
    } catch (final ClassNotFoundException cnfe) {
      // can be expected
      frameCodecClass = null;
    } catch (Throwable t) {
      // unexpected
      frameCodecClass = null;
      LOGGER.debug("Unable to setup netty http2 connection detection", t);
    }
    HTTP2_STREAM_FRAME_CODEC_CLS = codecClass;
    HTTP2_CONNECTION_CODEC_CLS = frameCodecClass;
    IS_SERVER_FIELD = isServerField;
  }

  public static boolean isHttp2FrameCodec(final ChannelHandler handler) {
    return HTTP2_STREAM_FRAME_CODEC_CLS != null && HTTP2_STREAM_FRAME_CODEC_CLS.isInstance(handler);
  }

  public static boolean isHttp2ConnectionCodec(final ChannelHandler handler) {
    return HTTP2_CONNECTION_CODEC_CLS != null && HTTP2_CONNECTION_CODEC_CLS.isInstance(handler);
  }

  public static boolean isServer(final ChannelHandler handler) {
    try {
      return IS_SERVER_FIELD != null && (boolean) IS_SERVER_FIELD.invokeExact(handler);
    } catch (Throwable t) {
      return false;
    }
  }
}
