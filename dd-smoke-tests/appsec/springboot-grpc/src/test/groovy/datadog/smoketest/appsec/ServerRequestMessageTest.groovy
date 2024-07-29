package datadog.smoketest.appsec


import okhttp3.Request

class ServerRequestMessageTest extends AbstractSpringBootWithGRPCAppSecTest {

  void 'test grpc.server.request.message address'() {
    setup:
    String url = "http://localhost:${httpPort}/${ROUTE}"
    def request = new Request.Builder()
      .url("${url}?message=${'/.htaccess'.bytes.encodeBase64()}")
      .get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("bye")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    and:
    waitForTraceCount(2) == 2
    rootSpans.size() == 2
    def grpcRootSpan = rootSpans.find { it.triggers }
    grpcRootSpan.triggers[0]['rule']['tags']['type'] == 'lfi'
    grpcRootSpan.triggers[0]['rule_matches'][0]['parameters']['address'][0] == 'grpc.server.request.message'
  }
}
