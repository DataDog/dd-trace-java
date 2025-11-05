package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.bootstrap.InstrumentationContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.utils.Charsets;

public class GrizzlyByteBodyInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.server.NIOInputStreamImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setInputBuffer")
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.glassfish.grizzly.http.io.InputBuffer"))),
        getClass().getName() + "$NIOInputStreamSetInputBufferAdvice");
    /* we're assuming here none of these methods call the other instrumented methods */
    transformer.applyAdvice(
        named("read").and(takesArguments(0)), getClass().getName() + "$NIOInputStreamReadAdvice");
    transformer.applyAdvice(
        named("read").and(takesArguments(1)).and(takesArgument(0, byte[].class)),
        getClass().getName() + "$NIOInputStreamReadByteArrayAdvice");
    transformer.applyAdvice(
        named("read").and(takesArguments(byte[].class, int.class, int.class)),
        getClass().getName() + "$NIOInputStreamReadByteArrayIntIntAdvice");
    transformer.applyAdvice(
        named("readBuffer").and(takesArguments(0).or(takesArguments(int.class))),
        getClass().getName() + "$NIOInputStreamReadBufferAdvice");
    transformer.applyAdvice(
        named("isFinished").and(takesArguments(0)),
        getClass().getName() + "$NIOInputStreamIsFinishedAdvice");
    transformer.applyAdvice(
        named("recycle").and(takesArguments(0)),
        getClass().getName() + "$NIOInputStreamRecycleAdvice");
    /* Possible alternative impl: call getBuffer() and register notifications.
    It would work even if the application relies on getBuffer() */
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class NIOInputStreamSetInputBufferAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final NIOInputStream thiz, @Advice.Argument(0) final InputBuffer inputBuffer) {
      // this is what grizzly defaults to
      Charset charset = StandardCharsets.ISO_8859_1;
      HttpHeader header = HttpHeaderFetchingHelper.fetchHttpHeader(inputBuffer);
      AttributeHolder attributes = header.getAttributes();
      Object attribute = attributes.getAttribute("datadog.intercepted_request_body");
      if (attribute != null) {
        return;
      }
      attributes.setAttribute("datadog.intercepted_request_body", Boolean.TRUE);

      String lengthHeader = header.getHeader("content-length");
      String encodingString = header.getCharacterEncoding();
      if (encodingString != null) {
        try {
          charset = Charsets.lookupCharset(encodingString);
        } catch (UnsupportedCharsetException use) {
          // purposefully left blank
        }
      }

      StoredByteBody storedByteBody = StoredBodyFactories.maybeCreateForByte(charset, lengthHeader);

      InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class)
          .put(thiz, storedByteBody);
    }
  }

  static class NIOInputStreamReadAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.This final NIOInputStream thiz, @Advice.Return int ret) {
      StoredByteBody storedByteBody =
          InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class).get(thiz);
      if (storedByteBody == null) {
        return;
      }
      if (ret == -1) {
        storedByteBody.maybeNotifyAndBlock();
        return;
      }
      storedByteBody.appendData(ret);
    }
  }

  static class NIOInputStreamReadByteArrayAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOInputStream thiz,
        @Advice.Argument(0) byte[] byteArray,
        @Advice.Return int ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      StoredByteBody storedByteBody =
          InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class).get(thiz);
      if (storedByteBody == null) {
        return;
      }

      if (ret == -1) {
        try {
          storedByteBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
        return;
      }
      storedByteBody.appendData(byteArray, 0, ret);
    }
  }

  static class NIOInputStreamReadByteArrayIntIntAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOInputStream thiz,
        @Advice.Argument(0) byte[] byteArray,
        @Advice.Argument(1) int off,
        @Advice.Return int ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }

      StoredByteBody storedByteBody =
          InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class).get(thiz);
      if (storedByteBody == null) {
        return;
      }
      if (ret == -1) {
        try {
          storedByteBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
        return;
      }
      storedByteBody.appendData(byteArray, off, off + ret);
    }
  }

  static class NIOInputStreamReadBufferAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.This final NIOInputStream thiz, @Advice.Return Buffer ret) {
      StoredByteBody storedByteBody =
          InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class).get(thiz);
      if (storedByteBody == null) {
        return;
      }
      int initPos = ret.position();
      byte[] bytes = new byte[ret.remaining()];
      ret.get(bytes);
      ret.position(initPos);

      storedByteBody.appendData(bytes, 0, bytes.length);
    }
  }

  static class NIOInputStreamIsFinishedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOInputStream thiz,
        @Advice.Return boolean ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      StoredByteBody storedByteBody =
          InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class).get(thiz);
      if (storedByteBody == null) {
        return;
      }
      if (ret) {
        try {
          storedByteBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
      }
    }
  }

  static class NIOInputStreamRecycleAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.This final NIOInputStream thiz) {
      InstrumentationContext.get(NIOInputStream.class, StoredByteBody.class).put(thiz, null);
    }
  }
}
