package datadog.smoketest.grpc.service;

import datadog.smoketest.grpc.IastGrpc;
import datadog.smoketest.grpc.IastOuterClass;
import io.grpc.stub.StreamObserver;
import java.net.HttpURLConnection;
import java.net.URL;

public class IastService extends IastGrpc.IastImplBase {

  @Override
  public void serverSideRequestForgery(
      final IastOuterClass.UrlRequest request,
      final StreamObserver<IastOuterClass.Response> responseObserver) {
    try {
      final URL target = new URL(request.getUrl());
      final HttpURLConnection conn = (HttpURLConnection) target.openConnection();
      conn.disconnect();
      IastOuterClass.Response response =
          IastOuterClass.Response.newBuilder().setMessage("SSRF triggered").build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (final Exception e) {
      responseObserver.onError(e);
    }
  }
}
