package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty70.JettyDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

@AutoService(Instrumenter.class)
public final class JettyCommitResponseInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public JettyCommitResponseInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpConnection";
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("commitResponse").and(takesArguments(1)).and(takesArgument(0, boolean.class)).or(
            named("completeResponse").and(takesArguments(0))
        ),
        JettyCommitResponseInstrumentation.class.getName() + "$CommitResponseAdvice"
    );
  }

  static class CommitResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean /* skip */ before(@Advice.This HttpConnection connection) {
      Generator generator = connection.getGenerator();
      if (generator.isCommitted()) {
        return false;
      }

      Request req = connection.getRequest();

      if (req.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
        return false;
      }

      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (!(existingSpan instanceof AgentSpan)) {
        return false;
      }
      AgentSpan span = (AgentSpan) existingSpan;
      RequestContext requestContext = span.getRequestContext();
      if (requestContext == null) {
        return false;
      }

      Response resp = connection.getResponse();

      Flow<Void> flow = DECORATE.callIGCallbackResponseAndHeaders(
          span, resp, resp.getStatus(), ExtractAdapter.Response.GETTER);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = requestContext.getBlockResponseFunction();
        if (brf != null) {
          boolean res = brf.tryCommitBlockingResponse(rba.getStatusCode(), rba.getBlockingContentType(), rba.getExtraHeaders());
          if (res) {
            return true;
          }
        }
      }

      return false;
    }
  }
}
