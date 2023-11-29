package datadog.smoketest

import datadog.armeria.grpc.Hello.HelloReply
import datadog.armeria.grpc.Hello.HelloRequest
import datadog.armeria.grpc.HelloServiceGrpc
import io.grpc.ManagedChannelBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ArmeriaGrpcClient {
  private static final Logger LOG = LoggerFactory.getLogger(ArmeriaGrpcClient)

  HelloServiceGrpc.HelloServiceBlockingStub blockingStub
  HelloServiceGrpc.HelloServiceFutureStub futureStub
  HelloServiceGrpc.HelloServiceStub asyncStub

  ArmeriaGrpcClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext())
    LOG.error("Connecting to {}:{}", host, port)
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  ArmeriaGrpcClient(ManagedChannelBuilder<?> channelBuilder) {
    def channel = channelBuilder.build()
    blockingStub = HelloServiceGrpc.newBlockingStub(channel)
    futureStub = HelloServiceGrpc.newFutureStub(channel)
    asyncStub = HelloServiceGrpc.newStub(channel)
  }

  HelloReply hello(int i) {
    HelloRequest request = HelloRequest.newBuilder().setName(i.toString()).build()
    return blockingStub.hello(request)
  }
}
