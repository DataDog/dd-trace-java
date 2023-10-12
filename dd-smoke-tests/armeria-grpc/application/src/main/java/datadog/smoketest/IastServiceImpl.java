package datadog.smoketest;

import datadog.armeria.grpc.Iast.Request;
import datadog.armeria.grpc.Iast.Request.Type;
import datadog.armeria.grpc.Iast.Response;
import datadog.armeria.grpc.IastServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.net.HttpURLConnection;
import java.net.URL;

public class IastServiceImpl extends IastServiceGrpc.IastServiceImplBase {

  @Override
  public void serverSideRequestForgery(
      final Request request, final StreamObserver<Response> responseObserver) {
    if (request.getType() != Type.URL) {
      responseObserver.onError(new IllegalArgumentException("Invalid type for request"));
    } else {
      try {
        final URL target = new URL(request.getUrl().getValue());
        final HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.disconnect();
        Response response = Response.newBuilder().setMessage("SSRF triggered").build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (final Exception e) {
        responseObserver.onError(e);
      }
    }
  }
}
