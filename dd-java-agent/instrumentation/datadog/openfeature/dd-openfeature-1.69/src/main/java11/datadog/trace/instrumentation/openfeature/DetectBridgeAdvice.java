package datadog.trace.instrumentation.openfeature;

import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.asm.Advice.Return;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;

@SuppressWarnings("unused")
public class DetectBridgeAdvice {
  @OnMethodExit(suppress = Throwable.class)
  public static void detect(@Return(readOnly = false) AgentBridge bridge) {
    bridge = JavaAgentBridge.connect(bridge);
  }
}
