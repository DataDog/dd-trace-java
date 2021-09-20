package datadog.trace.instrumentation.mongo4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import com.mongodb.internal.connection.CommandMessage;
import com.mongodb.internal.connection.InternalStreamConnection;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.mongo4.RequestSpanMap.RequestSpan;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Mongo4ClientInstrumentation extends Instrumenter.Tracing {

  public Mongo4ClientInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return (named("com.mongodb.MongoClientSettings$Builder")
            .and(declaresField(named("commandListeners"))))
        .or(named("com.mongodb.internal.connection.InternalStreamConnection"))
        .or(named("com.mongodb.internal.connection.LoggingCommandEventSender"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Mongo4ClientDecorator",
      packageName + ".BsonScrubber",
      packageName + ".BsonScrubber$1",
      packageName + ".BsonScrubber$2",
      packageName + ".Context",
      packageName + ".Tracing4CommandListener",
      packageName + ".RequestSpanMap",
      packageName + ".RequestSpanMap$RequestSpan"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        Mongo4ClientInstrumentation.class.getName() + "$Mongo4ClientAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("sendMessageAsync")),
        Mongo4ClientInstrumentation.class.getName() + "$Mongo4Suspend");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("sendSucceededEvent").or(named("sendFailedEvent"))),
        Mongo4ClientInstrumentation.class.getName() + "$Mongo4Resume");
  }

  public static class Mongo4ClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Tracing4CommandListener injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      if (!listeners.isEmpty()
          && listeners.get(listeners.size() - 1).getClass().getName().startsWith("datadog.")) {
        // we'll replace it, since it could be the 3.x async driver which is bundled with driver 4
        listeners.remove(listeners.size() - 1);
      }
      Tracing4CommandListener listener = new Tracing4CommandListener();
      listeners.add(listener);
      return listener;
    }

    @Advice.OnMethodExit
    public static void injectApplicationName(
        @Advice.Enter final Tracing4CommandListener listener,
        @Advice.Return final MongoClientSettings settings) {
      // record this clients application name so we can apply it to command spans
      if (null != listener) {
        listener.setApplicationName(settings.getApplicationName());
      }
    }
  }

  public static class Mongo4Suspend {
    @Advice.OnMethodExit
    public static void injectTracking(
        @Advice.This InternalStreamConnection self, @Advice.Argument(1) int rqId) {
      if (self.isClosed()) {
        return;
      }
      RequestSpan requestSpan = RequestSpanMap.getRequestSpan(rqId);
      AgentSpan span = requestSpan != null ? requestSpan.span : null;
      if (span != null) {
        requestSpan.isAsync = true;
        span.startThreadMigration();
      }
    }
  }

  public static class Mongo4Resume {
    @Advice.OnMethodEnter
    public static void injectTracking(@Advice.FieldValue("message") CommandMessage msg) {
      RequestSpan requestSpan = RequestSpanMap.getRequestSpan(msg.getId());
      AgentSpan span = requestSpan != null ? requestSpan.span : null;
      if (span != null && requestSpan.isAsync) {
        span.finishThreadMigration();
      }
    }
  }
}
