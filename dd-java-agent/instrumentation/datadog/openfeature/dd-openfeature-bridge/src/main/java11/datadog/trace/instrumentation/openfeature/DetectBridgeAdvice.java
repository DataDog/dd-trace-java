package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.bridge.AgentBridge;
import net.bytebuddy.asm.Advice;

public class DetectBridgeAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void detect(@Advice.Return(readOnly = false) AgentBridge bridge) {
    bridge = JavaAgentBridge.connect(bridge);
  }
}
