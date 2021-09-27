package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mongo.MongoClientDecorator.DECORATE;
import static datadog.trace.instrumentation.mongo.MongoClientDecorator.MONGO_QUERY;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ConnectionId;
import com.mongodb.event.CommandListener;
import com.mongodb.event.ConnectionListener;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
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
    return (namedOneOf(
                "com.mongodb.async.client.MongoClientSettings$Builder",
                "com.mongodb.MongoClientSettings$Builder")
            .and(declaresField(named("commandListeners"))))
        .or(named("com.mongodb.connection.DefaultClusterableServerFactory"))
        .or(named("com.mongodb.connection.CommandProtocol"))
        .or(named("com.mongodb.connection.WriteCommandProtocol"))
        .or(named("com.mongodb.connection.InternalStreamConnection"))
        .or(named("com.mongodb.event.ConnectionMessageReceivedEvent"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MongoClientDecorator",
      packageName + ".BsonScrubber",
      packageName + ".BsonScrubber$1",
      packageName + ".BsonScrubber$2",
      packageName + ".Context",
      packageName + ".AsyncTracingCommandListener",
      packageName + ".InterceptingConnectionListener",
      packageName + ".RequestSpanMap"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> store = new HashMap<>(2);
    store.put("org.bson.BsonDocument", "org.bson.ByteBuf");
    store.put("com.mongodb.connection.ConnectionId", RequestSpanMap.class.getName());
    return store;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        MongoAsyncClientInstrumentation.class.getName() + "$MongoAsyncClientAdvice");
    transformation.applyAdvice(
        isMethod().and(named("executeAsync")),
        MongoAsyncClientInstrumentation.class.getName() + "$MongoAsyncCommandAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("sendMessageAsync"))
            .and(takesArgument(2, named("com.mongodb.async.SingleResultCallback"))),
        MongoAsyncClientInstrumentation.class.getName() + "$SuspendSpanAdvice");
    transformation.applyAdvice(
        isConstructor()
            .and(
                ElementMatchers.<MethodDescription>isDeclaredBy(
                    named("com.mongodb.event.ConnectionMessageReceivedEvent")))
            .and(takesArgument(1, int.class)),
        MongoAsyncClientInstrumentation.class.getName() + "$RestoreSpanAdvice");
    transformation.applyAdvice(
        isConstructor()
            .and(
                ElementMatchers.<MethodDescription>isDeclaredBy(
                    named("com.mongodb.connection.DefaultClusterableServerFactory")))
            .and(takesArgument(7, named("com.mongodb.event.ConnectionListener")))
            .and(takesArgument(9, named("com.mongodb.event.CommandListener"))),
        MongoAsyncClientInstrumentation.class.getName() + "$PropagateConnectionIdAdvice");
    transformation.applyAdvice(
        isMethod().and(named("writeAsync")),
        MongoAsyncClientInstrumentation.class.getName() + "$DisableExecutorTaskPropagation");
  }

  /*
  This advice will register a custom 'AsyncTracingCommandListener' to fill in the command related span detials
  and finish that span once the command is done.
  */
  public static class MongoAsyncClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      if (!listeners.isEmpty()
          && listeners.get(listeners.size() - 1).getClass().getName().startsWith("datadog.")) {
        return;
      }
      listeners.add(
          new AsyncTracingCommandListener(
              InstrumentationContext.get(BsonDocument.class, ByteBuf.class)));
    }
  }

  /*
  An async command execution is wrapped within 'executeAsync' method which is defined by 'CommandProtocol' or
  'WriteCommandProtocol' classes (the difference between usages of those two classes seems to be that 'WriteCommandProtocol'
  is used to handle several commands in a batch).
  Thus, it makes sense to create and activate the command related span at that method borders. The command details will
  be filled in by the 'AsyncTracingCommandListener' registered in the previous advice since at the moment of entry
  to 'executeAsync' method those details are still not readily available - they are gradually unwrapped from the underlying
  connection and settings in several steps leading to calling the listener.
  */
  public static class MongoAsyncCommandAdvice {
    @Advice.OnMethodEnter
    public static AgentScope onAsyncEnter(
        @Advice.FieldValue("commandListener") CommandListener listener) {
      if (listener instanceof AsyncTracingCommandListener) {
        final AgentSpan span = startSpan(MONGO_QUERY);
        AgentScope scope = activateSpan(span);
        DECORATE.afterStart(span);
        ((AsyncTracingCommandListener) listener).storeCommandSpan(span);
        return scope;
      }
      return null;
    }

    @Advice.OnMethodExit
    public static void onAsyncExit(@Advice.Enter AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /*
  ThreadPoolExecutorInstrumentation is interfering with the mongo async client instrumentation when
  a command is executed in the completion handler of a previous command. The whole completion sequence
  can be captured as an executor task which will capture the active scope and reactivate it when
  the task is executed. Unfortunately, from the mongo perspective the previous command has already finished
  and as such the captured scope (related to the previous command) is incorrect.
  Here we attempt to 'fool' the executor instrumentation by artificially activating 'noopSpan', thus preventing
  the incorrect scope propagation.
  This needs to be done before mongo hands off the execution to the async io code - the last well known entry point
  seems to be 'com.mongodb.connection.InternalStreamConnection#writeAsync' which we are using as to activate and
  deactivate 'noopSpan'.
  */
  public static class DisableExecutorTaskPropagation {
    @Advice.OnMethodEnter
    public static TraceScope before() {
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * When a mongo command is 'sent out', possibly over network, we intercept the call and use this
   * advice to map the request id to an existing span and if the span is non-null we start the
   * thread migration.
   */
  public static class SuspendSpanAdvice {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.FieldValue("description") ConnectionDescription connection,
        @Advice.Argument(value = 1) int requestId) {
      RequestSpanMap map =
          InstrumentationContext.get(ConnectionId.class, RequestSpanMap.class)
              .get(connection.getConnectionId());
      AgentSpan span = map != null ? map.get(requestId) : null;
      if (span != null) {
        span.startThreadMigration();
      }
    }
  }

  /**
   * When a response to mongo command is deserialized we insert this advice, retrieve the span
   * corresponding to the request id and if not null we will finish the thread migration.
   */
  public static class RestoreSpanAdvice {
    @Advice.OnMethodExit
    public static void after(
        @Advice.Argument(0) ConnectionId connection, @Advice.Argument(1) int requestId) {
      RequestSpanMap map =
          InstrumentationContext.get(ConnectionId.class, RequestSpanMap.class).get(connection);
      AgentSpan span = map != null ? map.get(requestId) : null;
      if (span != null) {
        span.finishThreadMigration();
      }
    }
  }

  /**
   * A hook to allow configuring {@linkplain AsyncTracingCommandListener} when the associated mongo
   * connection is open and closed.
   */
  public static class PropagateConnectionIdAdvice {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.Argument(value = 7, readOnly = false) ConnectionListener connectionListener,
        @Advice.Argument(9) CommandListener commandListener) {
      if (commandListener instanceof AsyncTracingCommandListener) {
        // register a connection listener which will update the command listener with the correct
        // span map when the conneciton is open
        connectionListener =
            new InterceptingConnectionListener(
                InstrumentationContext.get(ConnectionId.class, RequestSpanMap.class),
                (AsyncTracingCommandListener) commandListener);
      }
    }
  }
}
