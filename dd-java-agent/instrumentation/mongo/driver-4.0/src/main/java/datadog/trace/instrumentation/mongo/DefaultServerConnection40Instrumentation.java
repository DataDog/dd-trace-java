package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpanWithoutScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.internal.connection.DefaultServerConnection;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.bson.BsonDocument;

@AutoService(InstrumenterModule.class)
public class DefaultServerConnection40Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DefaultServerConnection40Instrumentation() {
    super("mongo", "mongo-reactivestreams");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.internal.connection.DefaultServerConnection";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter",
      packageName + ".MongoDecorator",
      packageName + ".MongoCommentInjector",
      packageName + ".BsonScrubber",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("command"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("org.bson.BsonDocument"))),
        DefaultServerConnection40Instrumentation.class.getName() + "$CommandAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("commandAsync"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("org.bson.BsonDocument"))),
        DefaultServerConnection40Instrumentation.class.getName() + "$CommandAdvice");
  }

  public static class CommandAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This DefaultServerConnection connection,
        @Advice.Argument(value = 0) String dbName,
        @Advice.Argument(value = 1, readOnly = false) BsonDocument originalBsonDocument) {
      if (!MongoCommentInjector.INJECT_COMMENT) {
        return;
      }

      if (CallDepthThreadLocalMap.incrementCallDepth(DefaultServerConnection.class) > 0) {
        // we don't re-run the advice if the command goes through multiple overloads
        return;
      }

      AgentSpan span = startSpan(MongoDecorator.OPERATION_NAME);
      // scope is going to be closed by the MongoCommandListener
      activateSpanWithoutScope(span);

      String hostname = null;
      ConnectionDescription connectionDescription = connection.getDescription();
      if (connectionDescription != null && connectionDescription.getServerAddress() != null) {
        hostname = connectionDescription.getServerAddress().getHost();
      }

      String dbmComment = MongoCommentInjector.buildComment(span, hostname, dbName);
      if (dbmComment != null) {
        originalBsonDocument = MongoCommentInjector.injectComment(dbmComment, originalBsonDocument);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      if (!MongoCommentInjector.INJECT_COMMENT) {
        return;
      }

      CallDepthThreadLocalMap.decrementCallDepth(DefaultServerConnection.class);
    }
  }
}
