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
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.bson.BsonDocument;
import org.bson.BsonWriter;
import org.bson.ByteBuf;

/**
 * In Mongo 3.4 a new method was added to the {@linkplain org.bson.BsonWriter} type, making it
 * binary incompatible with pre 3.4 version. This instrumentation is 1:1 copy of the mongo 3.1
 * instrumentation version but for the fact that 3.4 compatible {@linkplain org.bson.BsonWriter} is
 * used.
 *
 * <p>Because muzzle does not support instrumentation priorities OOTB a rudimentary support was
 * added to {@linkplain MongoCommandListener} implementation which will make sure only the 'most
 * important' instrumentation will get to add its {@linkplain CommandListener} instance -
 * effectively overriding the previous instrumentation when necessary.
 */
@AutoService(Instrumenter.class)
public final class MongoClient34Instrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.WithTypeStructure {

  public MongoClient34Instrumentation() {
    super("mongo", "mongo-3.4");
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
      packageName + ".BsonScrubber34",
      packageName + ".BsonScrubber34$1",
      packageName + ".BsonScrubber34$2",
      packageName + ".MongoDecorator",
      packageName + ".MongoDecorator34",
      packageName + ".Context",
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("build"))
            .and(takesArguments(0))
            .and(isDeclaredBy(declaresField(named("applicationName")))),
        MongoClient34Instrumentation.class.getName() + "$MongoClientAdviceAppName");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("build"))
            .and(takesArguments(0))
            .and(not(isDeclaredBy(declaresField(named("applicationName"))))),
        MongoClient34Instrumentation.class.getName() + "$MongoClientAdviceNoAppName");
  }

  public static class MongoClientAdviceAppName {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static MongoCommandListener injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      return MongoCommandListener.tryRegister(
          new MongoCommandListener(
              2,
              MongoDecorator34.INSTANCE,
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

    public static void muzzleCheck(BsonWriter writer) {
      writer.writeDecimal128(null);
    }
  }

  public static class MongoClientAdviceNoAppName {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      MongoCommandListener.tryRegister(
          new MongoCommandListener(
              2,
              MongoDecorator34.INSTANCE,
              InstrumentationContext.get(BsonDocument.class, ByteBuf.class),
              InstrumentationContext.get(ConnectionDescription.class, CommandListener.class)),
          listeners);
    }

    public static void muzzleCheck(BsonWriter writer) {
      writer.writeDecimal128(null);
    }
  }
}
