package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.api.http.StoredCharBody;
import datadog.trace.bootstrap.InstrumentationContext;
import java.nio.CharBuffer;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.io.NIOReader;

public class GrizzlyCharBodyInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.server.NIOReaderImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setInputBuffer")
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.glassfish.grizzly.http.io.InputBuffer"))),
        getClass().getName() + "$NIOReaderSetInputBufferAdvice");
    transformer.applyAdvice(
        named("read").and(takesArguments(0)), getClass().getName() + "$NIOReaderReadAdvice");
    transformer.applyAdvice(
        named("read").and(takesArguments(1)).and(takesArgument(0, char[].class)),
        getClass().getName() + "$NIOReaderReadCharArrayAdvice");
    transformer.applyAdvice(
        named("read").and(takesArguments(char[].class, int.class, int.class)),
        getClass().getName() + "$NIOReaderReadCharArrayIntIntAdvice");
    transformer.applyAdvice(
        named("read").and(takesArguments(CharBuffer.class)),
        getClass().getName() + "$NIOReaderReadCharBufferAdvice");
    transformer.applyAdvice(
        named("isFinished").and(takesArguments(0)),
        getClass().getName() + "$NIOReaderIsFinishedAdvice");
    transformer.applyAdvice(
        named("recycle").and(takesArguments(0)), getClass().getName() + "$NIOReaderRecycleAdvice");
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class NIOReaderSetInputBufferAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final NIOReader thiz, @Advice.Argument(0) final InputBuffer inputBuffer) {
      HttpHeader header = HttpHeaderFetchingHelper.fetchHttpHeader(inputBuffer);
      AttributeHolder attributes = header.getAttributes();
      Object attribute = attributes.getAttribute("datadog.intercepted_request_body");
      if (attribute != null) {
        return;
      }

      attributes.setAttribute("datadog.intercepted_request_body", Boolean.TRUE);

      String lengthHeader = header.getHeader("content-length");
      StoredCharBody storedCharBody = StoredBodyFactories.maybeCreateForChar(lengthHeader);

      InstrumentationContext.get(NIOReader.class, StoredCharBody.class).put(thiz, storedCharBody);
    }
  }

  static class NIOReaderReadAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOReader thiz,
        @Advice.Return int ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      StoredCharBody storedCharBody =
          InstrumentationContext.get(NIOReader.class, StoredCharBody.class).get(thiz);
      if (storedCharBody == null) {
        return;
      }
      if (ret == -1) {
        try {
          storedCharBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
        return;
      }
      storedCharBody.appendData(ret);
    }
  }

  static class NIOReaderReadCharArrayAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOReader thiz,
        @Advice.Argument(0) char[] charArray,
        @Advice.Return int ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      StoredCharBody storedCharBody =
          InstrumentationContext.get(NIOReader.class, StoredCharBody.class).get(thiz);
      if (storedCharBody == null) {
        return;
      }
      if (ret == -1) {
        try {
          storedCharBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
        return;
      }
      storedCharBody.appendData(charArray, 0, ret);
    }
  }

  static class NIOReaderReadCharArrayIntIntAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOReader thiz,
        @Advice.Argument(0) char[] charArray,
        @Advice.Argument(1) int off,
        @Advice.Return int ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      StoredCharBody storedCharBody =
          InstrumentationContext.get(NIOReader.class, StoredCharBody.class).get(thiz);
      if (storedCharBody == null) {
        return;
      }
      if (ret == -1) {
        try {
          storedCharBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
        return;
      }
      storedCharBody.appendData(charArray, off, off + ret);
    }
  }

  static class NIOReaderReadCharBufferAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before(
        @Advice.This final NIOReader thiz,
        @Advice.Local("storedCharBody") StoredCharBody storedCharBody,
        @Advice.Argument(0) CharBuffer charBuffer) {
      storedCharBody = InstrumentationContext.get(NIOReader.class, StoredCharBody.class).get(thiz);
      if (storedCharBody == null) {
        return 0;
      }
      return charBuffer.position();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Local("storedCharBody") StoredCharBody storedCharBody,
        @Advice.Argument(0) CharBuffer charBuffer,
        @Advice.Enter int initPos,
        @Advice.Return int ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (storedCharBody == null || t != null) {
        return;
      }
      if (ret > 0) {
        int finalLimit = charBuffer.limit();
        int finalPos = charBuffer.position();
        charBuffer.limit(charBuffer.position());
        charBuffer.position(initPos);

        storedCharBody.appendData(charBuffer);

        charBuffer.limit(finalLimit);
        charBuffer.position(finalPos);
      } else if (ret == -1) {
        try {
          storedCharBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
      }
    }
  }

  static class NIOReaderIsFinishedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This final NIOReader thiz,
        @Advice.Return boolean ret,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      StoredCharBody storedCharBody =
          InstrumentationContext.get(NIOReader.class, StoredCharBody.class).get(thiz);
      if (storedCharBody == null) {
        return;
      }
      if (ret) {
        try {
          storedCharBody.maybeNotifyAndBlock();
        } catch (BlockingException be) {
          t = be;
        }
      }
    }
  }

  static class NIOReaderRecycleAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.This final NIOReader thiz) {
      InstrumentationContext.get(NIOReader.class, StoredCharBody.class).put(thiz, null);
    }
  }
}
