package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.Uri;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.source.WebModule;
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import scala.Tuple2;
import scala.collection.Iterator;

/** Propagates taint from a {@link Uri} to query strings fetched from it. */
@AutoService(Instrumenter.class)
public class UriInstrumentation extends Instrumenter.Iast implements Instrumenter.ForSingleType {
  public UriInstrumentation() {
    super("akka-http");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.model.Uri";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("queryString"))
            .and(returns(named("scala.Option")))
            .and(takesArguments(1))
            .and(takesArgument(0, named("java.nio.charset.Charset"))),
        UriInstrumentation.class.getName() + "$TaintQueryStringAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("rawQueryString"))
            .and(returns(named("scala.Option")))
            .and(takesArguments(0)),
        UriInstrumentation.class.getName() + "$TaintQueryStringAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("query"))
            .and(returns(named("akka.http.scaladsl.model.Uri$Query")))
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.nio.charset.Charset")))
            .and(takesArgument(1, named("akka.http.scaladsl.model.Uri$ParsingMode"))),
        UriInstrumentation.class.getName() + "$TaintQueryAdvice");
  }

  static class TaintQueryStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    static void after(@Advice.This Uri uri, @Advice.Return scala.Option<String> ret) {
      PropagationModule mod = InstrumentationBridge.PROPAGATION;
      if (mod == null || ret.isEmpty()) {
        return;
      }
      mod.taintIfInputIsTainted(ret.get(), uri);
    }
  }

  public static class TaintQueryAdvice {
    // bind uri to a variable of type Object so that this advice can also
    // be used from FromDataInstrumentaton
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(@Advice.This /*Uri*/ Object uri, @Advice.Return Uri.Query ret) {
      WebModule web = InstrumentationBridge.WEB;
      PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null || web == null || ret.isEmpty()) {
        return;
      }

      if (!prop.isTainted(uri)) {
        return;
      }

      Iterator<Tuple2<String, String>> iterator = ret.iterator();
      while (iterator.hasNext()) {
        Tuple2<String, String> pair = iterator.next();
        web.onParameterNames(Collections.singleton(pair._1()));
        prop.taint(SourceTypes.REQUEST_PARAMETER_VALUE, pair._1(), pair._2());
      }
    }
  }
}
