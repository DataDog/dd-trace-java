package datadog.smoketest.springboot.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SynchronousGreeter implements AutoCloseable {

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub impl;

  public SynchronousGreeter(int port) {
    this.channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    this.impl = GreeterGrpc.newBlockingStub(channel);
  }

  public String greet() {
    return impl.hello(Request.newBuilder().setMessage("hello").build()).getMessage();
  }

  @Override
  public void close() {
    channel.shutdown();
  }
}
