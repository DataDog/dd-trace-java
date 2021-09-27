package datadog.trace.instrumentation.mongo4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import com.mongodb.event.CommandListener;
import com.mongodb.internal.connection.CommandMessage;
import com.mongodb.internal.connection.InternalStreamConnection;
import com.mongodb.reactivestreams.client.MongoClient;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Mongo4ReactiveClientInstrumentation extends Instrumenter.Tracing {
  public Mongo4ReactiveClientInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.mongodb.internal.connection.InternalStreamConnection")
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
      packageName + ".Tracing4CommandListener"
    };
  }

  @Override
  public boolean isEnabled() {
    if (super.isEnabled()) {
      try {
        // should be activated only when the reactive MongoClient is available
        MongoClient.class.getName();
        return true;
      } catch (Throwable t) {
      }
    }
    return false;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // will start the command span thread migration
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("sendMessageAsync")),
        Mongo4ReactiveClientInstrumentation.class.getName() + "$Mongo4Suspend");
    // will finish the command span thread migration
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("sendSucceededEvent").or(named("sendFailedEvent"))),
        Mongo4ReactiveClientInstrumentation.class.getName() + "$Mongo4Resume");
  }

  public static class Mongo4Suspend {
    @Advice.OnMethodExit
    public static void injectTracking(
        @Advice.This InternalStreamConnection self,
        @Advice.Argument(1) int rqId,
        @Advice.FieldValue("commandListener") CommandListener listener) {
      if (self.isClosed()) {
        return;
      }
      AgentSpan span =
          (listener instanceof Tracing4CommandListener
              ? ((Tracing4CommandListener) listener).getSpan(rqId)
              : null);
      if (span != null) {
        span.startThreadMigration();
      }
    }
  }

  public static class Mongo4Resume {
    @Advice.OnMethodEnter
    public static void injectTracking(
        @Advice.FieldValue("commandListener") CommandListener listener,
        @Advice.FieldValue("message") CommandMessage msg) {
      AgentSpan span =
          (listener instanceof Tracing4CommandListener
              ? ((Tracing4CommandListener) listener).getSpan(msg.getId())
              : null);
      if (span != null) {
        span.finishThreadMigration();
      }
    }
  }
}
