package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;

public final class SqsReceiveResponseInternalAccess {

  private SqsReceiveResponseInternalAccess() {}

  public static void enter() {
    CallDepthThreadLocalMap.incrementCallDepth(SqsReceiveResponseInternalAccess.class);
  }

  public static void exit() {
    CallDepthThreadLocalMap.decrementCallDepth(SqsReceiveResponseInternalAccess.class);
  }

  public static boolean active() {
    return CallDepthThreadLocalMap.getCallDepth(SqsReceiveResponseInternalAccess.class) > 0;
  }
}
