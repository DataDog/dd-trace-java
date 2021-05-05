package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.event.CommandListener;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.bson.BsonDocument;
import org.bson.ByteBuf;

@AutoService(Instrumenter.class)
public final class MongoAsyncClientInstrumentation extends Instrumenter.Tracing {

  public MongoAsyncClientInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // the first class is only in older async drivers, and the second is in later
    // async drivers, but is also in version 4 of the driver. If we find the second
    // class, we'll activate the instrumentation, but take care to only add a subscriber
    // if no other datadog subscribers are present. If the driver 4 instrumentation
    // activates, it will remove other datadog subscribers
    return namedOneOf(
            "com.mongodb.async.client.MongoClientSettings$Builder",
            "com.mongodb.MongoClientSettings$Builder")
        .and(declaresField(named("commandListeners")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MongoClientDecorator",
      packageName + ".BsonScrubber",
      packageName + ".BsonScrubber$1",
      packageName + ".BsonScrubber$2",
      packageName + ".Context",
      packageName + ".TracingCommandListener"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.bson.BsonDocument", "org.bson.ByteBuf");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        MongoAsyncClientInstrumentation.class.getName() + "$MongoAsyncClientAdvice");
  }

  public static class MongoAsyncClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      if (!listeners.isEmpty()
          && listeners.get(listeners.size() - 1).getClass().getName().startsWith("datadog.")) {
        return;
      }
      listeners.add(
          new TracingCommandListener(
              InstrumentationContext.get(BsonDocument.class, ByteBuf.class)));
    }
  }
}
