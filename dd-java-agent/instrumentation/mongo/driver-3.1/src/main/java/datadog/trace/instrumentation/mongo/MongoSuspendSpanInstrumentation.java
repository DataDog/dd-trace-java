package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.event.CommandListener;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class MongoSuspendSpanInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public MongoSuspendSpanInstrumentation() {
    super("mongo", "mongo-suspend");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.mongodb.connection.InternalStreamConnection",
      "com.mongodb.internal.connection.InternalStreamConnection"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BsonScrubber",
      packageName + ".MongoDecorator",
      packageName + ".Context",
      packageName + ".MongoCommandListener",
      packageName + ".MongoCommandListener$SpanEntry"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "com.mongodb.connection.ConnectionDescription", "com.mongodb.event.CommandListener");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("sendMessageAsync")).and(takesArgument(1, int.class)),
        MongoSuspendSpanInstrumentation.class.getName() + "$MongoSuspend");
  }

  public static class MongoSuspend {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onBefore(
        @Advice.FieldValue("description") ConnectionDescription description,
        @Advice.Argument(1) int rqId) {
      CommandListener listener =
          InstrumentationContext.get(ConnectionDescription.class, CommandListener.class)
              .get(description);
      if (listener instanceof MongoCommandListener) {
        ((MongoCommandListener) listener).suspendSpan(rqId);
      }
    }
  }
}
