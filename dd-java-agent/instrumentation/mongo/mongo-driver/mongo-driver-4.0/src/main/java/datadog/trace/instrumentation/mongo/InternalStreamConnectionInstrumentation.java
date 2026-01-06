package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.mongodb.internal.async.SingleResultCallback;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class InternalStreamConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public InternalStreamConnectionInstrumentation() {
    super("mongo", "mongo-reactivestreams");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.internal.connection.InternalStreamConnection";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CallbackWrapper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("readAsync"))
            .and(takesArgument(1, named("com.mongodb.internal.async.SingleResultCallback"))),
        packageName + ".Arg1Advice");

    // this advice is applied on v5.2.0+ of the client where an extra parameter has been introduced
    transformer.applyAdvice(
        isMethod()
            .and(named("readAsync"))
            .and(takesArgument(2, named("com.mongodb.internal.async.SingleResultCallback"))),
        packageName + ".Arg2Advice");

    // THESE COULD END WITH AN EXCEPTION AND THE callback.onResult NOT CALLED so the continuation is
    // not cancelled/activated. FIXED in:
    // https://github.com/mongodb/mongo-java-driver/pull/783
    // https://github.com/mongodb/mongo-java-driver/commit/0eac1f09b9006899b2aed677dbcfdfe0ce94ab45
    transformer.applyAdvice(
        isMethod()
            .and(named("openAsync"))
            .and(takesArgument(0, named("com.mongodb.internal.async.SingleResultCallback"))),
        InternalStreamConnectionInstrumentation.class.getName() + "$OpenAsyncAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("writeAsync"))
            .and(takesArgument(1, named("com.mongodb.internal.async.SingleResultCallback"))),
        InternalStreamConnectionInstrumentation.class.getName() + "$WriteAsyncAdvice");
  }

  private static class OpenAsyncAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SingleResultCallback<Object> wrap(
        @Advice.Argument(value = 0, readOnly = false) SingleResultCallback<Object> callback) {
      callback = CallbackWrapper.wrapIfRequired(callback);
      return callback;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Enter final SingleResultCallback<Object> callback,
        @Advice.Thrown Throwable thrown) {
      if (null != thrown) {
        CallbackWrapper.cancel(callback);
      }
    }
  }

  private static class WriteAsyncAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SingleResultCallback<Object> wrap(
        @Advice.Argument(value = 1, readOnly = false) SingleResultCallback<Object> callback) {
      callback = CallbackWrapper.wrapIfRequired(callback);
      return callback;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Enter final SingleResultCallback<Object> callback,
        @Advice.Thrown Throwable thrown) {
      if (null != thrown) {
        CallbackWrapper.cancel(callback);
      }
    }
  }
}
