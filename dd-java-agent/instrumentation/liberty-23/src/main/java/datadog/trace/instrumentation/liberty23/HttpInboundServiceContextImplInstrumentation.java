package datadog.trace.instrumentation.liberty23;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.http.channel.internal.inbound.HttpInboundServiceContextImpl;
import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class HttpInboundServiceContextImplInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public static final String REQUEST_MSG_TYPE =
      "com.ibm.ws.http.channel.internal.HttpRequestMessageImpl";

  public HttpInboundServiceContextImplInstrumentation() {
    super("liberty");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        REQUEST_MSG_TYPE, "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(namedOneOf("sendResponseBody", "finishResponseMessage"))
            .and(takesArguments(1))
            .and(takesArgument(0, new ArrayOfTypeMatcher("com.ibm.wsspi.bytebuffer.WsByteBuffer")))
            .and(returns(void.class)),
        HttpInboundServiceContextImplInstrumentation.class.getName() + "$SyncAdviceBuffer");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.http.channel.internal.inbound.HttpInboundServiceContextImpl";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LibertyBlockingHelper",
      packageName + ".LibertyBlockingHelper$WsByteBufferImpl",
    };
  }

  /**
   * @see HttpInboundServiceContextImpl#sendResponseBody(WsByteBuffer[])
   * @see HttpInboundServiceContextImpl#finishResponseMessage(WsByteBuffer[])
   */
  static class SyncAdviceBuffer {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static BlockingException /* skip */ before(
        @Advice.This HttpInboundServiceContextImpl thiz,
        @Advice.Argument(0) WsByteBuffer[] buffers) {
      final int callDepth =
          CallDepthThreadLocalMap.incrementCallDepth(HttpInboundServiceContextImpl.class);
      if (callDepth > 0) {
        return null;
      }
      ContextStore store =
          InstrumentationContext.get(
              REQUEST_MSG_TYPE, "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
      Object o = store.get(thiz.getRequest());
      if (o == null) {
        return null;
      }
      return LibertyBlockingHelper.syncBufferEnter(thiz, buffers, (AgentSpan) o);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter BlockingException blockingException,
        @Advice.Thrown(readOnly = false) Throwable thrown) {
      CallDepthThreadLocalMap.decrementCallDepth(HttpInboundServiceContextImpl.class);
      if (blockingException != null) {
        thrown = blockingException;
      }
    }
  }
}
