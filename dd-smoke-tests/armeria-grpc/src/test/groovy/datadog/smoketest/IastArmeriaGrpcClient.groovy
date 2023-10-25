package datadog.smoketest

import datadog.armeria.grpc.Iast
import datadog.armeria.grpc.IastServiceGrpc
import io.grpc.ManagedChannelBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IastArmeriaGrpcClient {

  private static final Logger LOG = LoggerFactory.getLogger(IastArmeriaGrpcClient)

  IastServiceGrpc.IastServiceBlockingStub blockingStub
  IastServiceGrpc.IastServiceFutureStub futureStub
  IastServiceGrpc.IastServiceStub asyncStub

  IastArmeriaGrpcClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext())
    LOG.error("Connecting to {}:{}", host, port)
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  IastArmeriaGrpcClient(ManagedChannelBuilder<?> channelBuilder) {
    def channel = channelBuilder.build()
    blockingStub = IastServiceGrpc.newBlockingStub(channel)
    futureStub = IastServiceGrpc.newFutureStub(channel)
    asyncStub = IastServiceGrpc.newStub(channel)
  }

  Iast.Response ssrf(Iast.Request request) {
    return blockingStub.serverSideRequestForgery(request)
  }
}
