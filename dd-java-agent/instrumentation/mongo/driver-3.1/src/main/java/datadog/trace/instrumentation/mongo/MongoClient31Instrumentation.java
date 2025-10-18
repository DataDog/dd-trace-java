package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.event.CommandListener;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.bson.BsonDocument;
import org.bson.ByteBuf;

@AutoService(InstrumenterModule.class)
public final class MongoClient31Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {

  public MongoClient31Instrumentation() {
    super("mongo", "mongo-3.1");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.mongodb.MongoClientOptions$Builder",
      "com.mongodb.async.client.MongoClientSettings$Builder",
      "com.mongodb.MongoClientSettings$Builder"
    };
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("commandListeners"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BsonScrubber",
      packageName + ".BsonScrubber31",
      packageName + ".BsonScrubber31$1",
      packageName + ".BsonScrubber31$2",
      packageName + ".MongoDecorator",
      packageName + ".MongoDecorator31",
      packageName + ".Context",
      packageName + ".MongoCommentInjector",
      "datadog.trace.core.database.SharedDBCommenter",
      packageName + ".MongoCommandListener",
      packageName + ".MongoCommandListener$SpanEntry"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> map = new HashMap<>(2);
    map.put("org.bson.BsonDocument", "org.bson.ByteBuf");
    map.put("com.mongodb.connection.ConnectionDescription", "com.mongodb.event.CommandListener");
    return map;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("build"))
            .and(takesArguments(0))
            .and(isDeclaredBy(declaresField(named("applicationName")))),
        MongoClient31Instrumentation.class.getName() + "$MongoClientAdviceAppName");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("build"))
            .and(takesArguments(0))
            .and(not(isDeclaredBy(declaresField(named("applicationName"))))),
        MongoClient31Instrumentation.class.getName() + "$MongoClientAdviceNoAppName");
  }

  public static class MongoClientAdviceAppName {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static MongoCommandListener injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      return MongoCommandListener.tryRegister(
          new MongoCommandListener(
              1,
              MongoDecorator31.INSTANCE,
              InstrumentationContext.get(BsonDocument.class, ByteBuf.class),
              InstrumentationContext.get(ConnectionDescription.class, CommandListener.class)),
          listeners);
    }

    @Advice.OnMethodExit
    public static void updateApplicationName(
        @Advice.Enter final MongoCommandListener listener,
        @Advice.FieldValue(value = "applicationName") String applicationName) {
      if (listener != null) {
        listener.setApplicationName(applicationName);
      }
    }
  }

  public static class MongoClientAdviceNoAppName {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      MongoCommandListener.tryRegister(
          new MongoCommandListener(
              1,
              MongoDecorator31.INSTANCE,
              InstrumentationContext.get(BsonDocument.class, ByteBuf.class),
              InstrumentationContext.get(ConnectionDescription.class, CommandListener.class)),
          listeners);
    }
  }
}
