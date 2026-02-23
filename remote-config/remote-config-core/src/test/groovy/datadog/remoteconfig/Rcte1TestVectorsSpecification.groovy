package datadog.remoteconfig

import static java.nio.charset.StandardCharsets.UTF_8

import datadog.http.client.HttpClient
import datadog.http.client.HttpRequest
import datadog.http.client.HttpRequestBody
import datadog.http.client.HttpResponse
import datadog.http.client.HttpUrl
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import groovy.json.JsonSlurper
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class Rcte1TestVectorsSpecification extends DDSpecification {
  private static final int DEFAULT_POLL_PERIOD = 5000
  private final static JsonSlurper SLURPER = new JsonSlurper()
  private static final String KEY_ID = 'ed7672c9a24abda78872ee32ee71c7cb1d5235e8db4ecbf1ca28b9c50eb75d9e'
  private static final String PUBLIC_KEY = '7d3102e39abe71044d207550bda239c71380d013ec5a115f79f51622630054e6'

  final static HttpUrl URL = HttpUrl.parse('https://example.com/v0.7/config')
  HttpClient httpClient = Mock()
  AgentTaskScheduler scheduler = Mock()
  AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled = Mock()
  ConfigurationPoller poller

  AgentTaskScheduler.Task task
  HttpRequest request

  private HttpResponse buildOKResponse(String bodyStr) {
    Mock(HttpResponse) {
      code() >> 200
      isSuccessful() >> true
      bodyAsString() >> bodyStr
      body() >> { new ByteArrayInputStream(bodyStr.getBytes(UTF_8))}
      close() >> {}
    }
  }

  void setup() {
    injectSysConfig('dd.rc.targets.key.id', KEY_ID)
    injectSysConfig('dd.rc.targets.key', PUBLIC_KEY)
    injectSysConfig('remote_config.integrity_check.enabled', 'true')

    poller = new DefaultConfigurationPoller(
      Config.get(),
      '0.0.0',
      'containerid',
      'entityid',
      { -> URL.toString() },
      httpClient,
      scheduler
      )
  }

  private static String getFileContents(String baseFileName) {
    new File(Paths.get('.').toAbsolutePath().normalize().toFile(),
      "src/test/resources/rcte1/${baseFileName}.json").text
  }

  private static parseBody(HttpRequestBody body) {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      body.writeTo(bos)
      return SLURPER.parse(bos.toByteArray())
    }
  }

  void 'valid file'() {
    setup:
    Map savedConfig
    poller.addListener(
    Product.ASM_DD,
    { SLURPER.parse(it) } as ConfigurationDeserializer<Map>,
    { String cfgKey, Map map, PollingRateHinter hinter -> savedConfig = map } as ConfigurationChangesTypedListener<Map>
    )

    when:
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * httpClient.execute(_ as HttpRequest) >> {
      request = it[0]
      buildOKResponse(getFileContents('validOneFile'))
    }
    0 * _._
    savedConfig != null
  }

  void 'invalid file #baseFileName'() {
    setup:
    poller.addListener(
    Product.ASM_DD,
    { assert false, 'should never be called' } as ConfigurationDeserializer<Map>,
    { Object[] args -> } as ConfigurationChangesTypedListener<Map>
    )

    when:
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * httpClient.execute(_ as HttpRequest) >> {
      request = it[0]
      buildOKResponse(getFileContents(baseFileName))
    }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * httpClient.execute(_ as HttpRequest) >> {
      request = it[0]
      buildOKResponse('validOneFile')
    }
    0 * _._

    def body = parseBody(request.body())
    with(body) {
      with(client) {
        with(state) {
          has_error == true
          error.contains(message)
        }
      }
    }

    where:
    baseFileName | message
    'targetsSignedWithInvalidKey' | "Missing signature for key $KEY_ID"
    'tufTargetsInvalidSignature' | 'Signature verification failed for targets.signed'
    'tufTargetsInvalidTargetFileHash' | 'does not have the expected sha256 hash'
    'tufTargetsMissingTargetFile' | 'is in target_files, but not in targets.signed'
  }
}
