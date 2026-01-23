package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.webapp.WebAppErrorReport;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/**
 * Instrumentation to prevent the server from trying to send an error response by handling a
 * BlockingException (the response should've been committed already). Also avoids logging the
 * exception at SEVERE level.
 */
@AutoService(InstrumenterModule.class)
public class WebAppHandleExceptionInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public WebAppHandleExceptionInstrumentation() {
    super("liberty");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.webcontainer.webapp.WebApp";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("handleException"))
            .and(takesArguments(4))
            .and(takesArgument(0, Throwable.class))
            .and(returns(void.class)),
        WebAppHandleExceptionInstrumentation.class.getName() + "$HandleExceptionAdvice");
  }

  static class HandleExceptionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean /* skip */ before(@Advice.Argument(0) Throwable throwable_) {
      Throwable throwable = throwable_;
      if (throwable instanceof WebAppErrorReport) {
        throwable = throwable.getCause();
      }
      if (throwable instanceof BlockingException) {
        return throwable.getMessage().startsWith("Blocked response");
      }
      return false;
    }
  }
}
