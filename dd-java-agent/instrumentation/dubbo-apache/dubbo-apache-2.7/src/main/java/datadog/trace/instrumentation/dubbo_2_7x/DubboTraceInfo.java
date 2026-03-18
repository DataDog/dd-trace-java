package datadog.trace.instrumentation.dubbo_2_7x;

import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;

public class DubboTraceInfo {

  RpcInvocation invocation;

  RpcContext context;

  public DubboTraceInfo(RpcInvocation invocation, RpcContext context) {
    this.invocation = invocation;
    this.context = context;
  }

  public RpcInvocation getInvocation() {
    return invocation;
  }

  public void setInvocation(RpcInvocation invocation) {
    this.invocation = invocation;
  }

  public RpcContext getContext() {
    return context;
  }

  public void setContext(RpcContext context) {
    this.context = context;
  }


}
