package datadog.smoketest;

import datadog.armeria.grpc.Hello.HelloReply;
import datadog.armeria.grpc.Hello.HelloRequest;
import datadog.armeria.grpc.HelloServiceGrpc;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloServiceImpl extends HelloServiceGrpc.HelloServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(HelloServiceImpl.class);

  @Override
  public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    Tracer tracer = GlobalTracer.get();
    log.info("TT|" + tracer.getTraceId() + "|TS|" + tracer.getSpanId());

    String message = "Hello " + request.getName() + "!";
    responseObserver.onNext(HelloReply.newBuilder().setMessage(message).build());
    responseObserver.onCompleted();
  }
}
