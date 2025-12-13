package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpanWithoutScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.connection.Connection;
import com.mongodb.connection.ConnectionDescription;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
import net.bytebuddy.asm.Advice;
import org.bson.BsonDocument;

@AutoService(InstrumenterModule.class)
public class DefaultServerConnection31Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DefaultServerConnection31Instrumentation() {
    super("mongo", "mongo-3.1");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.connection.DefaultServerConnection";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      SharedDBCommenter.class.getName(),
      MongoCommentInjector.class.getName(),
      MongoDecorator.class.getName(),
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("command"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("org.bson.BsonDocument")))
            .and(takesArguments(5)),
        DefaultServerConnection31Instrumentation.class.getName() + "$CommandAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("commandAsync"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("org.bson.BsonDocument")))
            .and(takesArguments(6)),
        DefaultServerConnection31Instrumentation.class.getName() + "$CommandAdvice");
  }

  public static class CommandAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This Connection connection,
        @Advice.Argument(value = 0) String dbName,
        @Advice.Argument(value = 1, readOnly = false) BsonDocument originalBsonDocument) {
      if (!MongoCommentInjector.INJECT_COMMENT) {
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
  }
}
