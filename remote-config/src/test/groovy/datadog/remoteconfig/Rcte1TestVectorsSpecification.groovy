package datadog.remoteconfig

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import groovy.json.JsonSlurper
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class Rcte1TestVectorsSpecification extends DDSpecification {
  private static final int DEFAULT_POLL_PERIOD = 5000
  private final static JsonSlurper SLURPER = new JsonSlurper()
  private static final String KEY_ID = 'ed7672c9a24abda78872ee32ee71c7cb1d5235e8db4ecbf1ca28b9c50eb75d9e'
  private static final String PUBLIC_KEY = '7d3102e39abe71044d207550bda239c71380d013ec5a115f79f51622630054e6'

  final static HttpUrl URL = HttpUrl.get('https://example.com/v0.7/config')
  OkHttpClient okHttpClient = Mock()
  AgentTaskScheduler scheduler = Mock()
  AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled = Mock()
  ConfigurationPoller poller

  AgentTaskScheduler.Task task
  Request request
  Call call = Mock()

  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com').build()
  private Response buildOKResponse(String bodyStr) {
    ResponseBody body = ResponseBody.create(MediaType.get('application/json'), bodyStr)
    new Response.Builder()
      .request(REQUEST).protocol(Protocol.HTTP_1_1).message('OK').body(body).code(200).build()
  }

  void setup() {
    injectSysConfig('dd.rc.targets.key.id', KEY_ID)
    injectSysConfig('dd.rc.targets.key', PUBLIC_KEY)
    injectSysConfig('remote_config.integrity_check.enabled', 'true')

    poller = new ConfigurationPoller(
      Config.get(),
      '0.0.0',
      'containerid',
      'entityid',
      { -> URL.toString() },
      okHttpClient,
      scheduler
      )
  }

  private static String getFileContents(String baseFileName) {
    new File(Paths.get('.').toAbsolutePath().normalize().toFile(),
      "src/test/resources/rcte1/${baseFileName}.json").text
  }

  private parseBody(RequestBody body) {
    Buffer buffer = new Buffer()
    body.writeTo(buffer)
    byte[] bytes = new byte[buffer.size()]
    buffer.read(bytes)
    SLURPER.parse(bytes)
  }

  void 'valid file'() {
    setup:
    Map savedConfig
    poller.addListener(
      Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer<Map>,
      { String cfgKey, Map map, ConfigurationChangesListener.PollingRateHinter hinter -> savedConfig = map } as ConfigurationChangesTypedListener<Map>
      )

    when:
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(getFileContents('validOneFile')) }
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
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(getFileContents(baseFileName)) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse('validOneFile') }
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
