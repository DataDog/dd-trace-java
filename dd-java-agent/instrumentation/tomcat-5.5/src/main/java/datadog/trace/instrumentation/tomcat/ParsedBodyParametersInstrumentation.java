package datadog.trace.instrumentation.tomcat;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.apache.tomcat.util.http.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class ParsedBodyParametersInstrumentation extends Instrumenter.AppSec {
  private static final Logger log =
      LoggerFactory.getLogger(ParsedBodyParametersInstrumentation.class);

  public ParsedBodyParametersInstrumentation() {
    super("tomcat");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // so that muzzle fails on earlier tomcat versions
    return hasClassesNamed("org.apache.catalina.connector.CoyoteAdapter");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.apache.tomcat.util.http.Parameters");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // everything done in transformer()
    return;
  }

  @Override
  public AgentBuilder.Transformer transformer() {
    return new AgentBuilder.Transformer() {
      @Override
      public DynamicType.Builder<?> transform(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassLoader classLoader,
          JavaModule module) {
        // this might match more than one method, as some versions have
        // public variants that take an extra encoding parameter
        ElementMatcher.Junction<MethodDescription> processParameters =
            named("processParameters")
                .and(takesArgument(0, byte[].class))
                .and(takesArgument(1, int.class))
                .and(takesArgument(2, int.class));

        String adviceName = null;
        FieldList<FieldDescription.InDefinedShape> declaredFields =
            typeDescription.getDeclaredFields();
        for (FieldDescription.InDefinedShape declaredField : declaredFields) {
          // change occurred in 2011:
          // https://github.com/apache/tomcat/blame/c1c2e29d55ea41d76ab4bf688dbaafb9b100eadf/java/org/apache/tomcat/util/http/Parameters.java#L45
          if ("paramHashValues".equals(declaredField.getName())) {
            adviceName =
                ParsedBodyParametersInstrumentation.class.getName() + "$ProcessParametersAdvice";
            break;
          } else if ("paramHashStringArray".equals(declaredField.getName())) {
            adviceName =
                ParsedBodyParametersInstrumentation.class.getName() + "$ProcessParametersAdviceOld";
            break;
          }
        }

        if (adviceName == null) {
          log.warn("Do not know how to transform this {}", typeDescription.getName());
          return builder;
        }

        return new AgentBuilder.Transformer.ForAdvice()
            .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
            .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
            .advice(processParameters, adviceName)
            .transform(builder, typeDescription, classLoader, module);
      }
    };
  }

  @SuppressWarnings("Duplicates")
  public static class ProcessParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Parameters.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.FieldValue("paramHashValues") final Map<String, ArrayList<String>> paramHashValues,
        @Advice.Enter final int depth) {
      if (depth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Parameters.class);

      if (paramHashValues.isEmpty()) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      callback.apply(requestContext, paramHashValues);
    }
  }

  @SuppressWarnings("Duplicates")
  public static class ProcessParametersAdviceOld {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Parameters.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.FieldValue("paramHashStringArray") final Map<String, String[]> paramHashValues,
        @Advice.Enter final int depth) {
      if (depth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Parameters.class);

      if (paramHashValues.isEmpty()) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      callback.apply(requestContext, paramHashValues);
    }
  }
}
