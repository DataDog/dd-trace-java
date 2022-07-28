package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class HttpPostRequestDecoderInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForKnownTypes {
  public HttpPostRequestDecoderInstrumentation() {
    super(
        NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME,
        NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder",
      "io.netty.handler.codec.http.multipart.HttpPostStandardRequestDecoder",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("destroy").and(takesArguments(0)).and(isPublic()),
        getClass().getName() + "$DecoderDestroyAdvice");

    transformation.applyAdvice(
        named("addHttpData")
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.netty.handler.codec.http.multipart.InterfaceHttpData")))
            .and(isProtected()),
        getClass().getName() + "$AddHttpDataAdvice");
  }

  // make sure it lives until destroy()
  // earlier versions of ratpack released these before destroy
  static class AddHttpDataAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(@Advice.Argument(0) InterfaceHttpData data) {
      if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
        data.retain();
      }
    }
  }

  static class DecoderDestroyAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(@Advice.This final InterfaceHttpPostRequestDecoder decoder) {
      List<InterfaceHttpData> bodyHttpDatas = decoder.getBodyHttpDatas();
      if (bodyHttpDatas.isEmpty()) {
        return;
      }

      boolean doOnlyRelease = true;
      RuntimeException exc = null;

      AgentSpan agentSpan = activeSpan();
      if (agentSpan != null) {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Object, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestBodyProcessed());
        RequestContext requestContext = agentSpan.getRequestContext();

        if (requestContext != null && callback != null) {
          doOnlyRelease = false;

          Map<String, List<String>> attributes = new LinkedHashMap<>();
          for (InterfaceHttpData data : bodyHttpDatas) {
            if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
              String name = data.getName();
              List<String> values = attributes.get(name);
              if (values == null) {
                attributes.put(name, values = new ArrayList<>(1));
              }

              try {
                values.add(((Attribute) data).getValue());
              } catch (IOException e) {
                exc = new UndeclaredThrowableException(e);
              }
            }
            data.release();
          }

          callback.apply(requestContext, attributes);
        }
      }

      if (doOnlyRelease) {
        for (InterfaceHttpData data : bodyHttpDatas) {
          if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
            data.release();
          }
        }
      }

      if (exc != null) {
        throw exc;
      }
    }
  }
}
