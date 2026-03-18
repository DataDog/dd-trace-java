package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.async.TAsyncMethodCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncMethodCallConstructorAdvice {
  private static final Logger logger =
      LoggerFactory.getLogger(AsyncMethodCallConstructorAdvice.class);

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(
      @Advice.This TAsyncMethodCall objInst, @Advice.AllArguments final Object[] args) {
    if (args[3] instanceof AsyncMethodCallback) {
      final AsyncMethodCallback<Object> callback = (AsyncMethodCallback) args[3];
      try {
        ThriftConstants.setValue(
            TAsyncMethodCall.class,
            objInst,
            "callback",
            new DataDogAsyncMethodCallback<Object>(callback, null));
      } catch (Exception e) {
        logger.error("set value error:", e);
      }
    }

    //    if (allArguments[2] instanceof EnhancedInstance) {
    //      remotePeer = (String) ((EnhancedInstance) allArguments[2]).getSkyWalkingDynamicField();
    //    }
  }
}
