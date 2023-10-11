package datadog.smoketest

import datadog.grpc.Iast
import datadog.grpc.IastServiceGrpc
import io.grpc.ManagedChannelBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IastGrpcClient {

  private static final Logger LOG = LoggerFactory.getLogger(IastGrpcClient)

  IastServiceGrpc.IastServiceBlockingStub blockingStub
  IastServiceGrpc.IastServiceFutureStub futureStub
  IastServiceGrpc.IastServiceStub asyncStub

  IastGrpcClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext())
    LOG.error("Connecting to {}:{}", host, port)
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  IastGrpcClient(ManagedChannelBuilder<?> channelBuilder) {
    def channel = channelBuilder.build()
    blockingStub = IastServiceGrpc.newBlockingStub(channel)
    futureStub = IastServiceGrpc.newFutureStub(channel)
    asyncStub = IastServiceGrpc.newStub(channel)
  }

  Iast.Response ssrf(Iast.Request request) {
    return blockingStub.serverSideRequestForgery(request)
  }
}
