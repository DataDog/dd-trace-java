import datadog.remoteconfig.ConfigurationChangesListener
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import groovy.json.JsonOutput
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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

class ConfigurationPollerSpecification extends DDSpecification {
  final static HttpUrl URL = HttpUrl.get('https://example.com/v0.7/config')
  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com').build()
  private static final int DEFAULT_POLL_PERIOD = 5000

  private Response buildOKResponse(String bodyStr) {
    ResponseBody body = ResponseBody.create(MediaType.get('application/json'), bodyStr)
    new Response.Builder()
      .request(REQUEST).protocol(Protocol.HTTP_1_1).message('OK').body(body).code(200).build()
  }

  private final static JsonSlurper SLURPER = new JsonSlurper()

  OkHttpClient okHttpClient = Mock()
  AgentTaskScheduler scheduler = Mock()
  AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled = Mock()
  ConfigurationPoller poller

  AgentTaskScheduler.Task task
  Request request
  Call call = Mock()

  void setup() {
    injectSysConfig('dd.service', 'my_service')
    injectSysConfig('dd.env', 'my_env')
    poller = new ConfigurationPoller(
      Config.get(),
      '0.0.0',
      '',
      URL.toString(),
      okHttpClient,
      scheduler,
      )
  }

  private parseBody(RequestBody body) {
    Buffer buffer = new Buffer()
    body.writeTo(buffer)
    byte[] bytes = new byte[buffer.size()]
    buffer.read(bytes)
    SLURPER.parse(bytes)
  }

  void 'issues no request if there are no subscriptions'() {
    when:
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    0 * _._

    when:
    poller.addListener(Product.ASM_DD,
      {throw new RuntimeException('should not be caleed') } as ConfigurationDeserializer,
      { cfg, hinter -> true } as ConfigurationChangesListener)
    poller.removeListener(Product.ASM_DD)
    task.run(poller)

    then:
    0 * _._

    when:
    poller.stop()

    then:
    1 * scheduled.cancel()
  }

  void 'issues request if there is a subscription'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept(_, _, _ as ConfigurationChangesListener.PollingRateHinter) >> true
    0 * _._

    def body = parseBody(request.body())
    with(body) {
      cached_target_files == null
      with(client) {
        with(client_tracer) {
          app_version == ''
          env == 'my_env'
          language == 'java'
          runtime_id != ''
          service == 'my_service'
          tracer_version == '0.0.0'
        }
        id.size() == 36
        is_tracer == true
        products == ['ASM_DD']
        with(state) {
          config_states == []
          has_error == false
          root_version == 1
          targets_version == 0
        }
      }
    }
  }

  void 'reschedules if instructed to do so'() {
    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer, { cngKey, cfg, hinter ->
        hinter.suggestPollingRate(Duration.ofMillis(124))
        hinter.suggestPollingRate(Duration.ofMillis(123))
        hinter.suggestPollingRate(Duration.ofMillis(1230)) // higher is ignored
        true
      } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * scheduler.scheduleAtFixedRate(_, poller, 123, 123, TimeUnit.MILLISECONDS) >> scheduled
    1 * scheduled.cancel()
    0 * _._
  }

  void 'sets cached files and config state on second request'() {
    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    2.times { task.run(poller) }

    then:
    2 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    2 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    then:
    def body = parseBody(request.body())
    with(body) {
      cached_target_files.size() == 1
      with(cached_target_files[0]) {
        hashes == [
          [
            algorithm: 'sha256',
            hash: '6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819'
          ]
        ]
        length == 919
        path == 'employee/ASM_DD/1.recommended.json/config'
      }

      client.state.backend_client_state == 'foobar'

      client.state.config_states.size() == 1
      with(client.state.config_states[0]) {
        id == 'employee/ASM_DD/1.recommended.json/config'
        product == 'ASM_DD'
        version == 1
      }
    }
  }

  void 'removes cached file if configuration is pulled'() {
    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { call }
    1 * call.execute() >> {
      SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
        it['client_configs'] = []
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    then:
    def body = parseBody(request.body())
    with(body) {
      cached_target_files == null
    }
  }

  void 'does not update targets version number if there is an error'() {
    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { call }
    1 * call.execute() >> {
      SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
        def targetDecoded = Base64.decoder.decode(it['targets'])
        Map target = ConfigurationPollerSpecification.SLURPER.parse(targetDecoded)
        target['signed']['targets'].remove('employee/ASM_DD/1.recommended.json/config')
        target['signed']['version'] = 42
        it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(target).getBytes('UTF-8'))
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    then:
    def body = parseBody(request.body())
    with(body) {
      cached_target_files == null // previous hash should be cleared too
      with(client['state']) {
        backend_client_state == 'foobar'
        config_states == []
        has_error == true
        error == 'Told to apply config for employee/ASM_DD/1.recommended.json/config but no corresponding entry ' +
          'exists in targets.targets_signed.targets'
        root_version == 1
        targets_version == 23337393
      }
    }
  }

  void 'applies configuration only if the hash has changed'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    2.times { task.run(poller) }

    then:
    2 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    2 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept(_, _, _ as ConfigurationChangesListener.PollingRateHinter) >> true
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> {
      SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
        byte[] fileDecoded = Base64.decoder.decode(it['target_files'][0]['raw'])
        byte[] newFile = new byte[fileDecoded.length + 1]
        System.arraycopy(fileDecoded, 0, newFile, 0, fileDecoded.length)
        newFile[fileDecoded.length] = '\n'
        it['target_files'][0]['raw'] = Base64.encoder.encodeToString(newFile)
        def targetDecoded = Base64.decoder.decode(it['targets'])
        def target = ConfigurationPollerSpecification.SLURPER.parse(targetDecoded)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] =
          new BigInteger((byte[])MessageDigest.getInstance('SHA-256').digest(newFile)).toString(16)
        it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(target).getBytes('UTF-8'))
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    1 * listener.accept('employee/ASM_DD/1.recommended.json/config', _, _ as ConfigurationChangesListener.PollingRateHinter) >> true
    0 * _._
  }

  void 'configuration cannot be applied without hashes'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> {
      task = it[0]
      scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> {
      SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
        def targetDecoded = Base64.decoder.decode(it['targets'])
        def target = ConfigurationPollerSpecification.SLURPER.parse(targetDecoded)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes'].remove('sha256')
        it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(target).getBytes('UTF-8'))
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept('employee/ASM_DD/1.recommended.json/config', _, _)
    0 * _._

    then:
    def body = parseBody(request.body())
    with(body.client.state) {
      config_states.size() == 0
      error == 'No sha256 hash present for employee/ASM_DD/1.recommended.json/config'
      targets_version == 0
    }
  }

  void 'encoded file is not valid base64 data'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> {
      SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
        byte[] fileDecoded = Base64.decoder.decode(it['target_files'][0]['raw'])
        it['target_files'][0]['raw'] = Base64.encoder.encodeToString(fileDecoded) + '##'
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept(_, _, _)
    0 * _._

    then:
    def body = parseBody(request.body())
    with(body.client.state) {
      error == 'Could not get file contents from remote config, file employee/ASM_DD/1.recommended.json/config'
    }
  }

  void 'deserializer can return null to indicate no config should be applied'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { null } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._
  }

  void 'rejects configuration if the hash is wrong'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> {
      SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
        def targetDecoded = Base64.decoder.decode(it['targets'])
        def target = ConfigurationPollerSpecification.SLURPER.parse(targetDecoded)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] = '0'
        it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(target).getBytes('UTF-8'))
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._
  }

  void 'accepts an empty object as a response to indicate no changes'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { throw new RuntimeException('should not be called' ) } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse('{}') }
    0 * _._
  }

  void 'unapplies configurations it has stopped seeing'() {
    ConfigurationChangesListener<Map<String, Object>> listener = Mock()
    String cfgWithoutAsm = SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = []
      def targetDecoded = Base64.decoder.decode(it['targets'])
      def target = ConfigurationPollerSpecification.SLURPER.parse(targetDecoded)
      target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] = 'aec070645fe53ee3b3763059376134f058cc337247c978add178b6ccdfb0019f'
      it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(target).getBytes('UTF-8'))
      JsonOutput.toJson(it)
    }

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept(_, { it != null }, _) >> false // should still unapply afterwards even if failed
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(cfgWithoutAsm) }
    1 * listener.accept(_, null, _) >> true
    0 * _._

    // the next time it doesn't unapply it
    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(cfgWithoutAsm) }
    0 * _._
  }

  void 'support multiple configurations'() {
    ConfigurationChangesListener<Map<String, Object>> listener = Mock()
    String multiConfigs = SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = ['employee/ASM_DD/1.recommended.json/config', 'employee/ASM_DD/2.suggested.json/config']
      JsonOutput.toJson(it)
    }

    String noConfigs = SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = []
      JsonOutput.toJson(it)
    }

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    //apply first configuration
    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept('employee/ASM_DD/1.recommended.json/config', { it != null }, _) >> false // should still unapply afterwards even if failed
    0 * _._

    // apply second configuration
    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(multiConfigs) }
    1 * listener.accept('employee/ASM_DD/2.suggested.json/config', { it != null }, _) >> true
    0 * _._

    //remove second configuration if it no longer sent
    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept('employee/ASM_DD/2.suggested.json/config', null, _) >> false // should still unapply afterwards even if failed
    0 * _._

    //remove all configurations
    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(noConfigs) }
    1 * listener.accept('employee/ASM_DD/1.recommended.json/config', null, _) >> false // should still unapply afterwards even if failed
    0 * _._
  }

  void 'exception applying one config should not prevent others from being applied'() {
    String newConfigKey = 'datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config'

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { throw new RuntimeException('throw here') } as ConfigurationChangesListener)
    poller.addListener(Product.LIVE_DEBUGGING,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> {
      SLURPER.parseText(SAMPLE_RESP_BODY).with {
        it['client_configs'] << newConfigKey
        def targetDecoded = Base64.decoder.decode(it['targets'])
        def target = ConfigurationPollerSpecification.SLURPER.parse(targetDecoded)
        target['signed']['targets'][newConfigKey] = [
          custom: [v: 3],
          hashes: [
            sha256: '7a38bf81f383f69433ad6e900d35b3e2385593f76a7b7ab5d4355b8ba41ee24b',
          ],
          length: 72,
        ]
        it['target_files'] << [
          path: newConfigKey,
          raw: Base64.encoder.encodeToString('{"foo":"bar"}'.getBytes('UTF-8'))
        ]

        it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(target).getBytes('UTF-8'))
        buildOKResponse(JsonOutput.toJson(it))
      }
    }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    then:
    def body = parseBody(request.body())
    with(body) {
      client.state.config_states.size() == 2
      with(client.state.config_states[0]) {
        id == 'employee/ASM_DD/1.recommended.json/config'
        product == 'ASM_DD'
        version == 1
      }
      with(client.state.config_states[1]) {
        id == newConfigKey
        product == 'LIVE_DEBUGGING'
        version == 3
      }
    }
  }

  void 'bad responses'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> resp
    0 * _._

    where:
    resp << [
      // 404 with body
      new Response.Builder().request(REQUEST)
      .protocol(Protocol.HTTP_1_1)
      .message('Not Found').code(404).body(
      ResponseBody.create(MediaType.get('text/plain'), 'not found!')).build(),
      // 404 without body
      new Response.Builder().request(REQUEST)
      .protocol(Protocol.HTTP_1_1)
      .message('Not Found').code(404).build(),
      // success, no body
      new Response.Builder().request(REQUEST)
      .protocol(Protocol.HTTP_1_1)
      .message('Created').code(201).build(),
      // not json
      new Response.Builder()
      .request(REQUEST).protocol(Protocol.HTTP_1_1).message('OK').body(ResponseBody.create(MediaType.get('text/plain'), SAMPLE_RESP_BODY)).code(200).build()
    ]
  }

  void 'body does not satisfy format'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    // targets is not a string
    1 * call.execute() >> { buildOKResponse(bodyStr) }
    0 * _._

    where:
    bodyStr << [
      '{"targets": []}',
      '{"targets": "ZZZZ="}',
      """{"targets": "${Base64.encoder.encodeToString('{"signed": "string"}'.getBytes('UTF-8'))}"}"""
    ]
  }

  void 'reportable errors'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { true } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    // targets is not a string
    1 * call.execute() >> { buildOKResponse(bodyStr) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    // targets is not a string
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    def body = parseBody(request.body())
    with(body) {
      client.state.config_states == []
      with(client.state) {
        has_error == true
        error == errorMsg
      }
    }

    where:
    bodyStr | errorMsg
    // not a valid key
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'][0] = 'foobar'
      JsonOutput.toJson(it)
    } | 'Not a valid config key: foobar'

    // no file for the given key
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['target_files'] = []
      JsonOutput.toJson(it)
    } | 'No content for employee/ASM_DD/1.recommended.json/config'

    // told to apply config that is not subscribed
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = ['datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config']
      JsonOutput.toJson(it)
    } | 'Told to handle config key datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config, but the product LIVE_DEBUGGING is not being handled'
  }

  void 'the max size is exceeded'() {
    ConfigurationDeserializer deserializer = Mock()
    when:
    poller.addListener(Product.ASM_DD,
      deserializer,
      { throw new RuntimeException('throw here') } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> {
      SLURPER.parseText(SAMPLE_RESP_BODY).with {
        it['target_files'] << [
          path: 'foo/bar',
          raw: 'a' * Config.get().remoteConfigMaxPayloadSizeBytes
        ]
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._
  }

  void 'can listen for changes in a local file'() {
    File file = Files.createTempFile(null, '.json').toFile()
    def savedConf

    when:
    poller.addFileListener(file,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { path, conf, hinter -> savedConf = conf } as ConfigurationChangesListener)
    poller.start()
    file << '{"foo":"bar"}'.getBytes('UTF-8')

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    savedConf['foo'] == 'bar'
    0 * _._

    when:
    file.delete()
    file << '{"foo":"xpto"}'.getBytes('UTF-8')
    task.run(poller)

    then:
    savedConf['foo'] == 'xpto'
    0 * _._

    cleanup:
    file.delete()
  }

  void 'distributes features'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addFeaturesListener('asm',
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    1 * listener.accept(
      _,
      { cfg -> cfg['enabled'] == true },
      _ as ConfigurationChangesListener.PollingRateHinter) >> true
    0 * _._
  }

  void 'distributes features upon subscribing'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addFeaturesListener('foobar',
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { Object[] args -> throw new RuntimeException('should not be called') } as ConfigurationChangesListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    0 * _._

    when:
    poller.addFeaturesListener('asm',
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)

    then:
    1 * listener.accept(
      _,
      { cfg -> cfg['enabled'] == true },
      _ as ConfigurationChangesListener.PollingRateHinter) >> true
    0 * _._
  }

  void 'error applying features'() {
    boolean called

    when:
    poller.addFeaturesListener('asm',
      {true } as ConfigurationDeserializer<Boolean>,
      { Object[] args -> called = true; throw new RuntimeException('throws') } as ConfigurationChangesListener<Boolean>)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    0 * _._
    called == true
    // error does not escape
  }

  void 'removing feature listeners'() {
    ConfigurationChangesListener listener = Mock()

    when:
    poller.addFeaturesListener('foobar',
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { Object[] args -> throw new RuntimeException('should not be called') } as ConfigurationChangesListener)
    poller.addFeaturesListener('asm',
      {true } as ConfigurationDeserializer<Boolean>,
      listener)
    poller.removeFeaturesListener('asm')
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    0 * _._ // in particular, listener is not called

    when:
    poller.removeFeaturesListener('foobar')
    task.run(poller)

    then:
    0 * _._ // not even a request is made
  }

  private static final String SAMPLE_RESP_BODY = """
{
   "client_configs" : [
      "employee/ASM_DD/1.recommended.json/config"
   ],
   "roots" : [],
   "target_files" : [
      {
         "path" : "employee/ASM_DD/1.recommended.json/config",
         "raw" : "${Base64.encoder.encodeToString(SAMPLE_APPSEC_CONFIG.getBytes('UTF-8'))}"
      },
      {
         "path" : "employee/ASM_DD/2.suggested.json/config",
         "raw" : "${Base64.encoder.encodeToString(SAMPLE_APPSEC_CONFIG.getBytes('UTF-8'))}"
      }
   ],
   "targets" : "${Base64.encoder.encodeToString(SAMPLE_TARGETS.getBytes('UTF-8'))}"
}
  """

  private static final String SAMPLE_APPSEC_CONFIG = '''
{
    "version": "2.2",
    "metadata": {
        "rules_version": "1.3.1"
    },
    "rules": [
        {
            "id": "crs-913-110",
            "name": "Acunetix",
            "tags": {
                "type": "security_scanner",
                "crs_id": "913110",
                "category": "attack_attempt"
            },
            "conditions": [
                {
                    "parameters": {
                        "inputs": [
                            {
                                "address": "server.request.headers.no_cookies"
                            }
                        ],
                        "list": [
                            "acunetix-product"
                        ]
                    },
                    "operator": "phrase_match"
                }
            ],
            "transformers": [
                "lowercase"
            ]
        }
    ]
}
'''

  private static final String SAMPLE_TARGETS = '''
{
   "signatures" : [
      {
         "keyid" : "5c4ece41241a1bb513f6e3e5df74ab7d5183dfffbd71bfd43127920d880569fd",
         "sig" : "766871ed1acc60ef35f9f24262682283a55e79334f5154486176033b67568aed82fe8139a1f78689b96473537f0a2e55c8365d50bff345ea9ac350d57b90390d"
      }
   ],
   "signed" : {
      "_type" : "targets",
      "custom" : {
         "opaque_backend_state" : "foobar"
      },
      "expires" : "2022-09-17T12:49:15Z",
      "spec_version" : "1.0.0",
      "targets" : {
         "employee/ASM_DD/1.recommended.json/config" : {
            "custom" : {
               "v" : 1
            },
            "hashes" : {
               "sha256" : "6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819"
            },
            "length" : 919
         },
         "employee/ASM_DD/2.suggested.json/config" : {
            "custom" : {
               "v" : 1
            },
            "hashes" : {
               "sha256" : "6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819"
            },
            "length" : 919
         },
         "employee/CWS_DD/2.default.policy/config" : {
            "custom" : {
               "v" : 2
            },
            "hashes" : {
               "sha256" : "2f075fcaa9bdfc96bfc30d5a18711fbebf59d2cb3f3b5258d41ebdc1b1a54569"
            },
            "length" : 34805
         }
      },
      "version" : 23337393
   }
}
'''

  private static final FEATURES_RESP_BODY = JsonOutput.toJson(
  client_configs: ['datadog/2/FEATURES/asm_features_activation/config'],
  roots: [],
  target_files: [
    [
      path: 'datadog/2/FEATURES/asm_features_activation/config',
      raw: Base64.encoder.encodeToString('{"asm":{"enabled":true}}'.getBytes('UTF-8'))
    ]
  ],
  targets: Base64.encoder.encodeToString(JsonOutput.toJson([
    signed: [
      expires: '2022-09-17T12:49:15Z',
      spec_version: '1.0.0',
      targets: [
        'datadog/2/FEATURES/asm_features_activation/config': [
          custom: [
            v: 1
          ],
          hashes: [
            sha256: '159658ab85be7207761a4111172b01558394bfc74a1fe1d314f2023f7c656db'
          ],
          length : 24,
        ]
      ],
      version: 23337393
    ]
  ]).getBytes(StandardCharsets.UTF_8)))
}
