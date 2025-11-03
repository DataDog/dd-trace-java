package datadog.trace.instrumentation.dubbo_3_2;

import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.tri.RequestMetadata;

public class DubboMetadata {
  private RpcInvocation rpcInvocation;

  private RequestMetadata requestMetadata;

  public DubboMetadata(RpcInvocation rpcInvocation, RequestMetadata requestMetadata) {
    this.rpcInvocation = rpcInvocation;
    this.requestMetadata = requestMetadata;
  }
  public RpcInvocation getRpcInvocation() {
    return rpcInvocation;
  }
  public RequestMetadata getRequestMetadata() {
    return requestMetadata;
  }
  public void setRpcInvocation(RpcInvocation rpcInvocation) {
    this.rpcInvocation = rpcInvocation;
  }
  public void setRequestMetadata(RequestMetadata requestMetadata) {
    this.requestMetadata = requestMetadata;
  }
}
