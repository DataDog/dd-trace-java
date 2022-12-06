package datadog.smoketest.springboot.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AsynchronousGreeter implements AutoCloseable {

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterStub impl;

  public AsynchronousGreeter(int port) {
    this.channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    this.impl = GreeterGrpc.newStub(channel);
  }

  public String greet(String message) {
    final AtomicReference<String> response = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    StreamObserver<Response> observer =
        new StreamObserver<Response>() {
          @Override
          public void onNext(Response value) {
            response.set(value.getMessage());
          }

          @Override
          public void onError(Throwable t) {
            throw new AssertionError(t);
          }

          @Override
          public void onCompleted() {
            latch.countDown();
          }
        };
    impl.hello(Request.newBuilder().setMessage(message).build(), observer);
    try {
      latch.await(30, TimeUnit.SECONDS);
      return response.get();
    } catch (InterruptedException e) {
      return "timed out";
    }
  }

  @Override
  public void close() {
    channel.shutdown();
  }
}
