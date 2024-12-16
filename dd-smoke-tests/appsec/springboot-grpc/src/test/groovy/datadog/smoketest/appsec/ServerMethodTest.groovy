package datadog.smoketest.appsec


import okhttp3.Request
import spock.lang.Shared

class ServerMethodTest extends AbstractSpringBootWithGRPCAppSecTest {

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_custom_rules.json"

  @Override
  ProcessBuilder createProcessBuilder() {
    // We run this here to ensure it runs before starting the process. Child setupSpec runs after parent setupSpec,
    // so it is not a valid location.
    mergeRules(customRulesPath, [
      [
        id          : '__test_server_method_bock',
        name        : 'test rule to block on server method',
        tags        : [
          type      : 'test',
          category  : 'test',
          confidence: '1',
        ],
        conditions  : [
          [
            parameters: [
              inputs: [[address: 'grpc.server.method']],
              regex : 'Greeter',
            ],
            operator  : 'match_regex',
          ]
        ],
        transformers: [],
        on_match    : ['block']
      ]
    ])
    return super.createProcessBuilder()
  }

  void 'test grpc.server.method address'() {
    setup:
    String url = "http://localhost:${httpPort}/${ROUTE}"
    def request = new Request.Builder()
      .url("${url}?message=${'Hello!'.bytes.encodeBase64()}")
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
    grpcRootSpan != null
    def match = grpcRootSpan.triggers[0]['rule_matches'][0]
    match != null
    match['parameters'][0]['address'] == 'grpc.server.method'
    match['parameters'][0]['value'] == 'smoketest.Greeter/Hello'
  }
}
