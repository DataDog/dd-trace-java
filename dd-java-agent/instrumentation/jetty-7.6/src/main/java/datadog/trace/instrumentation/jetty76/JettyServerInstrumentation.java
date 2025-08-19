package datadog.trace.instrumentation.jetty76;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_FIN_DISP_LIST_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty76.JettyDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.ProductActivation;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty.ConnectionHandleRequestVisitor;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

@AutoService(InstrumenterModule.class)
public final class JettyServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.AbstractHttpConnection";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      packageName + ".RequestURIDataAdapter",
      "datadog.trace.instrumentation.jetty.JettyBlockResponseFunction",
      "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    // The lifecycle of these objects are aligned, and are recycled by jetty, minimizing leak risk.
    return singletonMap("org.eclipse.jetty.http.Generator", "org.eclipse.jetty.server.Response");
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new ConnectionHandleRequestVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), JettyServerInstrumentation.class.getName() + "$ConstructorAdvice");
    transformer.applyAdvice(
        named("handleRequest").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$HandleRequestAdvice");
    transformer.applyAdvice(
        named("reset").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$ResetAdvice");
  }

  public static class ConnectionHandleRequestVisitorWrapper implements AsmVisitorWrapper {

    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      if (Config.get().getAppSecActivation() == ProductActivation.FULLY_DISABLED) {
        return classVisitor;
      }

      return new ConnectionHandleRequestVisitor(
          Opcodes.ASM7, classVisitor, "org/eclipse/jetty/server/AbstractHttpConnection");
    }
  }

  /**
   * HttpConnection's have both a generator and a response instance. The generator is what writes
   * out the final bytes that are sent back to the requestor. We read the status code from the
   * response in ResetAdvice, but in some cases the final status code is only set in the generator
   * directly, not the response. (For example, this happens when an exception is thrown and jetty
   * must send a 500 status.) We use the JettyGeneratorInstrumentation to ensure that the response
   * is updated when the generator is. Since the status on the response is reset when the connection
   * is reset, this minor change in behavior is inconsequential. This advice provides the needed
   * link between generator -> response to enable this.
   */
  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void link(
        @Advice.FieldValue("_generator") final Generator generator,
        @Advice.FieldValue("_response") final Response response) {
      InstrumentationContext.get(Generator.class, Response.class).put(generator, response);
    }
  }

  /**
   * The handleRequest call denotes the earliest point at which the incoming request is fully
   * parsed. This allows us to read the headers from the request to extract propagation info.
   */
  public static class HandleRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.This final AbstractHttpConnection connection,
        @Advice.Local("newSpan") AgentSpan span) {
      Request req = connection.getRequest();

      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (existingSpan instanceof AgentSpan) {
        // Request already gone through initial processing, so just activate the span.
        return ((AgentSpan) existingSpan).attach();
      }

      final Context parentContext = DECORATE.extract(req);
      final Context context = DECORATE.startSpan(req, parentContext);
      final ContextScope scope = context.attach();
      span = spanFromContext(context);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, req, req, parentContext);

      req.setAttribute(DD_SPAN_ATTRIBUTE, span);
      req.setAttribute(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
      req.setAttribute(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Enter final ContextScope scope) {
      // Span is finished when the connection is reset, so we only need to close the scope here.
      scope.close();
    }
  }

  /**
   * Jetty ensures that connections are reset immediately after the response is sent. This provides
   * a reliable point to finish the server span at the last possible moment.
   */
  public static class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final AbstractHttpConnection connection) {
      Request req = connection.getRequest();
      Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (spanObj instanceof AgentSpan) {
        final AgentSpan span = (AgentSpan) spanObj;
        DECORATE.onResponse(span, connection);
        DECORATE.beforeFinish(span);
        span.finish();
      }

      // Jetty doesn't always call async listeners
      // Finish the dispatch listener span if it hasn't already
      Runnable r = (Runnable) req.getAttribute(DD_FIN_DISP_LIST_SPAN_ATTRIBUTE);
      if (r != null) {
        r.run();
      }
    }
  }
}
