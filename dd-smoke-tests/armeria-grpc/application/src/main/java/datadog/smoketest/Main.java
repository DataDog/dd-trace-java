package datadog.smoketest;

import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.util.concurrent.CompletableFuture;

public class Main {
  public static void main(String[] args) {
    int port = Integer.parseInt(System.getProperty("armeria.http.port", "8080"));

    ServerBuilder sb = Server.builder();
    sb.http(port);

    GrpcService grpcService =
        GrpcService.builder()
            .addService(new HelloServiceImpl())
            .addService(new IastServiceImpl())
            .addService(ProtoReflectionService.newInstance())
            .supportedSerializationFormats(GrpcSerializationFormats.values())
            .enableUnframedRequests(true)
            .build();
    sb.service(grpcService);

    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();
  }
}
