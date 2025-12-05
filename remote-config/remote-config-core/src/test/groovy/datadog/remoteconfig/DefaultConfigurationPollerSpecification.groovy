package datadog.remoteconfig

import cafe.cryptography.ed25519.Ed25519PrivateKey
import cafe.cryptography.ed25519.Ed25519PublicKey
import cafe.cryptography.ed25519.Ed25519Signature
import datadog.remoteconfig.state.ProductListener
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

import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState.ConfigState.APPLY_STATE_ERROR

class DefaultConfigurationPollerSpecification extends DDSpecification {
  final static HttpUrl URL = HttpUrl.get('https://example.com/v0.7/config')
  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com').build()
  private static final int DEFAULT_POLL_PERIOD = 5000
  private static final String KEY_ID = 'TEST_KEY_ID'
  private static final Ed25519PrivateKey PRIVATE_KEY = Ed25519PrivateKey.generate(new SecureRandom())
  private static final Ed25519PublicKey PUBLIC_KEY = PRIVATE_KEY.derivePublic()

  private Response buildOKResponse(String bodyStr) {
    ResponseBody body = ResponseBody.create(MediaType.get('application/json'), bodyStr)
    new Response.Builder()
      .request(REQUEST).protocol(Protocol.HTTP_1_1).message('OK').body(body).code(200).build()
  }

  private final static JsonSlurper SLURPER = new JsonSlurper()

  OkHttpClient okHttpClient = Mock()
  AgentTaskScheduler scheduler = Mock()
  AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled = Mock()
  DefaultConfigurationPoller poller

  AgentTaskScheduler.Task task
  Request request
  Call call = Mock()
  Supplier<String> configUrlSupplier = { -> URL.toString() } as Supplier<String>

  void setup() {
    injectSysConfig('dd.rc.targets.key.id', KEY_ID)
    injectSysConfig('dd.rc.targets.key', new BigInteger(1, PUBLIC_KEY.toByteArray()).toString(16))
    injectSysConfig('dd.service', 'my_service')
    injectSysConfig('dd.env', 'my_env')
    injectSysConfig('dd.remote_config.integrity_check.enabled', 'true')
    poller = new DefaultConfigurationPoller(
      Config.get(),
      '0.0.0',
      '',
      '',
      { -> configUrlSupplier.get() } as Supplier<String>,
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
      {throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { cfg, hinter -> true } as ConfigurationChangesTypedListener)
    poller.removeListeners(Product.ASM_DD)
    task.run(poller)

    then:
    0 * _._

    when:
    poller.stop()

    then:
    1 * scheduled.cancel()
  }

  void 'issues request if there is a subscription'() {
    ConfigurationChangesTypedListener listener = Mock()

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
    1 * listener.accept(_, _, _ as PollingRateHinter)
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

  void 'issues no request if the config url supplier returns null'() {
    setup:
    def deserializer = Mock(ConfigurationDeserializer)
    def listener = Mock(ConfigurationChangesTypedListener)
    configUrlSupplier = { -> }

    when:
    poller.addListener(Product.ASM_DD, deserializer, listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    0 * _._
  }

  void 'once the supplier provides a url it is not called anymore'() {
    setup:
    def deserializer = Mock(ConfigurationDeserializer)
    def listener = Mock(ConfigurationChangesTypedListener)
    configUrlSupplier = Mock(Supplier)

    when:
    poller.addListener(Product.ASM_DD, deserializer, listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    2.times { task.run(poller) }

    then:
    1 * configUrlSupplier.get() >> URL.toString()
    2 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    2 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * deserializer.deserialize(_) >> true
    1 * listener.accept(_, _, _)
    0 * _._
  }

  void 'handle product listeners per config'() {
    def deserializer = Mock(ConfigurationDeserializer)
    ConfigurationChangesTypedListener activationListener = Mock(ConfigurationChangesTypedListener)
    ConfigurationChangesTypedListener sampleRateListener = Mock(ConfigurationChangesTypedListener)
    def respBody = JsonOutput.toJson(
      client_configs: [
        'datadog/2/ASM_FEATURES/asm_features_activation/config',
        'datadog/2/ASM_FEATURES/api_security/sample_rate',
      ],
      roots: [],
      target_files: [
        [
          path: 'datadog/2/ASM_FEATURES/asm_features_activation/config',
          raw: Base64.encoder.encodeToString('{"asm":{"enabled":true}}'.getBytes('UTF-8'))
        ],
        [
          path: 'datadog/2/ASM_FEATURES/api_security/sample_rate',
          raw: Base64.encoder.encodeToString('{"api_security": {"request_sample_rate": 0.1}'.getBytes('UTF-8'))
        ]
      ],
      targets: signAndBase64EncodeTargets(
      signed: [
        expires: '2022-09-17T12:49:15Z',
        spec_version: '1.0.0',
        targets: [
          'datadog/2/ASM_FEATURES/asm_features_activation/config': [
            custom: [ v: 1 ],
            hashes: [ sha256: '159658ab85be7207761a4111172b01558394bfc74a1fe1d314f2023f7c656db' ],
            length : 24,
          ],
          'datadog/2/ASM_FEATURES/api_security/sample_rate': [
            custom: [v:1],
            hashes: [ sha256: 'bc898b7eb75d9fd0ddee1c1a556bc3c528dd41382950aa86e48816f792d01494' ],
            length : 45,
          ]
        ],
        version: 1
      ]
      ))

    def noConfigs = SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = []
      JsonOutput.toJson(it)
    }

    when:
    poller.addListener(Product.ASM_FEATURES, 'asm_features_activation', deserializer, activationListener)
    poller.addListener(Product.ASM_FEATURES, 'api_security', deserializer, sampleRateListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }

    then:
    2 * deserializer.deserialize(_) >> true
    1 * activationListener.accept('datadog/2/ASM_FEATURES/asm_features_activation/config', _, _)
    1 * sampleRateListener.accept('datadog/2/ASM_FEATURES/api_security/sample_rate', _, _)
    0 * _._

    //remove all configurations
    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(noConfigs) }
    1 * activationListener.accept('datadog/2/ASM_FEATURES/asm_features_activation/config', _, _)
    1 * sampleRateListener.accept('datadog/2/ASM_FEATURES/api_security/sample_rate', _, _)
    0 * _._
  }

  void 'processing happens for all listeners'() {
    def deserializer = Mock(ConfigurationDeserializer)
    List<ConfigurationChangesTypedListener> listeners = (1..5).collect { Mock(ConfigurationChangesTypedListener) }
    def respBody = JsonOutput.toJson(
      client_configs: [
        'datadog/2/ASM_FEATURES/asm_features_activation/config',
        'foo/ASM_DD/bar/config',
        'foo/ASM/bar/config',
        'foo/ASM_DATA/bar/config',
        'foo/LIVE_DEBUGGING/bar/config',
      ],
      roots: [],
      target_files: [
        [
          path: 'datadog/2/ASM_FEATURES/asm_features_activation/config',
          raw: Base64.encoder.encodeToString('{"asm":{"enabled":true}}'.getBytes('UTF-8'))
        ],
        [
          path: 'foo/ASM_DD/bar/config',
          raw: ''
        ],
        [
          path: 'foo/ASM/bar/config',
          raw: ''
        ],
        [
          path: 'foo/ASM_DATA/bar/config',
          raw: ''
        ],
        [
          path: 'foo/LIVE_DEBUGGING/bar/config',
          raw: ''
        ],
      ],
      targets: signAndBase64EncodeTargets(
      signed: [
        expires: '2022-09-17T12:49:15Z',
        spec_version: '1.0.0',
        targets: [
          'datadog/2/ASM_FEATURES/asm_features_activation/config': [
            custom: [ v: 1 ],
            hashes: [ sha256: '159658ab85be7207761a4111172b01558394bfc74a1fe1d314f2023f7c656db' ],
            length : 24,
          ],
          'foo/ASM_DD/bar/config': [
            custom: [v:1],
            hashes: [ sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' ],
            length : 0,
          ],
          'foo/ASM/bar/config': [
            custom: [v:1],
            hashes: [ sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' ],
            length : 0,
          ],
          'foo/ASM_DATA/bar/config': [
            custom: [v:1],
            hashes: [ sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' ],
            length : 0,
          ],
          'foo/LIVE_DEBUGGING/bar/config': [
            custom: [v:1],
            hashes: [ sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' ],
            length : 0,
          ],
        ],
        version: 1
      ]
      ))

    when:
    poller.addListener(Product.ASM_DD, deserializer, listeners[1])
    poller.addListener(Product.ASM, deserializer, listeners[2])
    poller.addListener(Product.ASM_DATA, deserializer, listeners[3])
    poller.addListener(Product.LIVE_DEBUGGING, deserializer, listeners[0])
    poller.addListener(Product.ASM_FEATURES, deserializer, listeners[4])
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }

    then:
    1 * deserializer.deserialize(_) >> true
    1 * listeners[0].accept(*_)
    1 * deserializer.deserialize(_) >> true
    1 * listeners[1].accept(*_)
    1 * deserializer.deserialize(_) >> true
    1 * listeners[2].accept(*_)
    1 * deserializer.deserialize(_) >> true
    1 * listeners[3].accept(*_)
    1 * deserializer.deserialize(_) >> true
    1 * listeners[4].accept(*_)

    then:
    0 * _
  }

  void 'reschedules if instructed to do so'() {
    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer, { cngKey, cfg, hinter ->
        hinter.suggestPollingRate(Duration.ofMillis(124))
        hinter.suggestPollingRate(Duration.ofMillis(123))
        hinter.suggestPollingRate(Duration.ofMillis(1230)) // higher is ignored
      } as ConfigurationChangesTypedListener)
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
      { Object[] args -> } as ConfigurationChangesTypedListener)
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
        id == '1.recommended.json'
        product == 'ASM_DD'
        version == 1
      }
    }
  }

  void 'removes cached file if configuration is pulled'() {
    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> } as ConfigurationChangesTypedListener)
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
      { Object[] args -> } as ConfigurationChangesTypedListener)
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
        it['target_files'] = []
        def targetDecoded = Base64.decoder.decode(it['targets'])
        Map target = SLURPER.parse(targetDecoded)
        target['signed']['targets'].remove('employee/ASM_DD/1.recommended.json/config')
        target['signed']['version'] = 42
        it['targets'] = signAndBase64EncodeTargets(target)
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
    ConfigurationChangesTypedListener listener = Mock()

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
    1 * listener.accept(_, _, _ as PollingRateHinter)
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
        def target = SLURPER.parse(targetDecoded)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] =
          new BigInteger((byte[])MessageDigest.getInstance('SHA-256').digest(newFile)).toString(16)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['length'] += 1
        it['targets'] = signAndBase64EncodeTargets(target)
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    1 * listener.accept('employee/ASM_DD/1.recommended.json/config', _, _ as PollingRateHinter)
    0 * _._
  }

  void 'configuration cannot be applied without hashes'() {
    ConfigurationChangesTypedListener listener = Mock()

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
        def target = SLURPER.parse(targetDecoded)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes'].remove('sha256')
        it['targets'] = signAndBase64EncodeTargets(target)
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
    ConfigurationChangesTypedListener listener = Mock()

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
    ConfigurationChangesTypedListener listener = Mock()

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
    ConfigurationChangesTypedListener listener = Mock()

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
        def target = SLURPER.parse(targetDecoded)
        target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] = '0'
        it['targets'] = signAndBase64EncodeTargets(target)
        buildOKResponse(JsonOutput.toJson(it))
      }
    }
    0 * _._
  }

  void 'accepts an empty object as a response to indicate no changes'() {
    given:
    def listener = Mock(ConfigurationChangesTypedListener)
    def deserializer = Mock(ConfigurationDeserializer)

    when:
    poller.addListener(Product.ASM_DD, deserializer, listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse('{}') }
    0 * deserializer._
    0 * listener._
    0 * _._
  }

  void 'accepts HTTP 204 as a response to indicate no changes'() {
    given:
    Response resp = new Response.Builder()
      .request(REQUEST)
      .protocol(Protocol.HTTP_1_1)
      .message('No Content')
      .body(ResponseBody.create(MediaType.parse("application/json"), ""))
      .code(204)
      .build()
    def listener = Mock(ConfigurationChangesTypedListener)
    def deserializer = Mock(ConfigurationDeserializer)

    when:
    poller.addListener(Product.ASM_DD, deserializer, listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> resp
    0 * deserializer._
    0 * listener._
    0 * _
  }

  void 'applies and remove called for product listener'() {
    ProductListener listener = Mock()
    String cfgWithoutAsm = SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = []
      def targetDecoded = Base64.decoder.decode(it['targets'])
      def target = SLURPER.parse(targetDecoded)
      target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] = 'aec070645fe53ee3b3763059376134f058cc337247c978add178b6ccdfb0019f'
      it['targets'] = signAndBase64EncodeTargets(target)
      JsonOutput.toJson(it)
    }

    when:
    poller.addListener(Product.ASM_DD, listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    1 * listener.accept(_, { it != null }, _) >> false
    1 * listener.commit(_)
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    // no listnenr.commit() should be called as no changed where detected
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(cfgWithoutAsm) }
    1 * listener.remove(_, _)
    1 * listener.commit(_)
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(cfgWithoutAsm) }
    // no listnenr.commit() should be called as no changed where detected
    0 * _._
  }

  void 'unapplies configurations it has stopped seeing'() {
    ConfigurationChangesTypedListener<Map<String, Object>> listener = Mock()
    String cfgWithoutAsm = SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = []
      def targetDecoded = Base64.decoder.decode(it['targets'])
      def target = SLURPER.parse(targetDecoded)
      target['signed']['targets']['employee/ASM_DD/1.recommended.json/config']['hashes']['sha256'] = 'aec070645fe53ee3b3763059376134f058cc337247c978add178b6ccdfb0019f'
      it['targets'] = signAndBase64EncodeTargets(target)
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
    1 * listener.accept(_, null, _)
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
    ConfigurationChangesTypedListener<Map<String, Object>> listener = Mock()
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
    1 * listener.accept('employee/ASM_DD/2.suggested.json/config', { it != null }, _)
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
    String newConfigId = '1ba66cc9-146a-3479-9e66-2b63fd580f48'
    String newConfigKey = "datadog/2/LIVE_DEBUGGING/${newConfigId}/config"

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> throw new RuntimeException('throw here') } as ConfigurationChangesTypedListener)
    poller.addListener(Product.LIVE_DEBUGGING,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> } as ConfigurationChangesTypedListener)
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
        def target = SLURPER.parse(targetDecoded)
        target['signed']['targets'][newConfigKey] = [
          custom: [v: 3],
          hashes: [
            sha256: '7a38bf81f383f69433ad6e900d35b3e2385593f76a7b7ab5d4355b8ba41ee24b',
          ],
          length: '{"foo":"bar"}'.size(),
        ]
        it['target_files'] << [
          path: newConfigKey,
          raw: Base64.encoder.encodeToString('{"foo":"bar"}'.getBytes('UTF-8'))
        ]

        it['targets'] = signAndBase64EncodeTargets(target)
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
      def first = client.state.config_states[0]
      def second = client.state.config_states[1]
      def liveDebuggingConfig = first.product == 'LIVE_DEBUGGING'? first : second
      def asmConfig = first.product == 'ASM_DD'? first : second
      with(liveDebuggingConfig) {
        id == newConfigId
        product == 'LIVE_DEBUGGING'
        version == 3
        apply_error == null
      }
      with(asmConfig) {
        id == '1.recommended.json'
        product == 'ASM_DD'
        version == 1
        apply_state == APPLY_STATE_ERROR
        apply_error == "throw here"
      }
    }
  }

  void 'bad responses'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { } as ConfigurationChangesTypedListener)
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
      { } as ConfigurationChangesTypedListener)
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

  void 'reportable errors #errorMsg'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { } as ConfigurationChangesTypedListener)
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

    // two reportable errors
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = ['foobar', 'employee/ASM_DD/1.recommended.json/config']
      it['target_files'] = []
      JsonOutput.toJson(it)
    } | 'Failed to apply configuration due to 2 errors:\n (1) Not a valid config key: foobar\n (2) No content for employee/ASM_DD/1.recommended.json/config\n'

    // in target_files, but not targets.signed.targets
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      def targetDecoded = Base64.decoder.decode(it['targets'])
      Map targets = SLURPER.parse(targetDecoded)
      targets['signed']['targets'] = [:]
      it['targets'] = signAndBase64EncodeTargets(targets)
      JsonOutput.toJson(it)
    } | 'Path employee/ASM_DD/1.recommended.json/config is in target_files, but not in targets.signed'

    // told to apply config that is not subscribed
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      it['client_configs'] = ['datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config']
      JsonOutput.toJson(it)
    } | 'Told to handle config key datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config, but the product LIVE_DEBUGGING is not being handled'

    // Invalid signature
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      def targetDecoded = Base64.decoder.decode(it['targets'])
      Map targets = SLURPER.parse(targetDecoded)
      targets['signatures'][0]['sig'] = '59a6478aba87d171261e6995faaa8e36c95c3e75436c4e82f11ac625220e13b703ce9b912ee0731415121b5a47aa2abdb398a60656b7701b15e606c6327c880e'
      it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(targets).getBytes('UTF-8'))
      JsonOutput.toJson(it)
    } | 'Signature verification failed for targets.signed. Key id: TEST_KEY_ID'

    // Structurally invalid signature
    SLURPER.parse(SAMPLE_RESP_BODY.bytes).with {
      def targetDecoded = Base64.decoder.decode(it['targets'])
      Map targets = SLURPER.parse(targetDecoded)
      targets['signatures'][0]['sig'] = 'a' * 128
      it['targets'] = Base64.encoder.encodeToString(JsonOutput.toJson(targets).getBytes('UTF-8'))
      JsonOutput.toJson(it)
    } | 'Error reading signature or canonicalizing targets.signed: Invalid scalar representation'

  }

  void 'reports error during deserialization'() {
    when:
    poller.addListener(Product.ASM_DD,
      { throw new RuntimeException('my deserializer error') } as ConfigurationDeserializer,
      { } as ConfigurationChangesTypedListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> {
      task = it[0]
      scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> call
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    def body = parseBody(request.body())
    with(body) {
      with(client.state.config_states.first()) {
        apply_state == APPLY_STATE_ERROR
        apply_error == 'my deserializer error'
      }
    }
  }

  void 'reports error applying configuration'() {
    when:
    poller.addListener(Product.ASM_DD,
      { true } as ConfigurationDeserializer,
      { Object[] args -> throw new RuntimeException('error applying config') } as ConfigurationChangesTypedListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> {
      task = it[0]
      scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> call
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    def body = parseBody(request.body())
    with(body) {
      with(client.state.config_states.first()) {
        apply_state == APPLY_STATE_ERROR
        apply_error == 'error applying config'
      }
    }
  }

  void 'the max size is exceeded'() {
    ConfigurationDeserializer deserializer = Mock()
    when:
    poller.addListener(Product.ASM_DD,
      deserializer,
      { throw new RuntimeException('throw here') } as ConfigurationChangesTypedListener)
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
      { path, conf, hinter -> savedConf = conf } as ConfigurationChangesTypedListener)
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
    ConfigurationChangesTypedListener listener = Mock()

    when:
    poller.addListener(Product.ASM_FEATURES,
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
      { cfg -> cfg['asm']['enabled'] == true },
      _ as PollingRateHinter)
    0 * _._
  }

  void 'distributes features upon subscribing'() {
    ConfigurationChangesTypedListener listener = Mock()

    when:
    poller.addListener(Product.ASM_FEATURES,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { Object[] args -> throw new RuntimeException('should not be called') } as ConfigurationChangesTypedListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    and:
    poller.removeListeners(Product.ASM_FEATURES)

    when:
    poller.addListener(Product.ASM_FEATURES,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener)
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    1 * listener.accept(
      _,
      { cfg -> cfg['asm']['enabled'] == true },
      _ as PollingRateHinter)
    0 * _._
  }

  void 'distribute config across multiple listeners for same subscriber'() {
    ConfigurationChangesTypedListener listener1 = Mock()
    ConfigurationChangesTypedListener listener2 = Mock()

    when:
    poller.addListener(Product.ASM_FEATURES,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener1)
    poller.addListener(Product.ASM_FEATURES,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      listener2)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    1 * listener1.accept(
      _,
      { cfg -> cfg['asm']['enabled'] == true },
      _ as PollingRateHinter)
    1 * listener2.accept(
      _,
      { cfg -> cfg['api_security']['request_sample_rate'] == 0.1 },
      _ as PollingRateHinter)
    0 * _._
  }

  void 'error applying features'() {
    boolean called

    when:
    poller.addListener(Product.ASM_FEATURES,
      {true } as ConfigurationDeserializer<Boolean>,
      { Object[] args -> called = true; throw new RuntimeException('throws') } as ConfigurationChangesTypedListener<Boolean>)
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
    ConfigurationChangesTypedListener listener = Mock()

    when:
    poller.addListener(Product._UNKNOWN,
      { throw new RuntimeException('should not be called') } as ConfigurationDeserializer,
      { Object[] args -> throw new RuntimeException('should not be called') } as ConfigurationChangesTypedListener)
    poller.addListener(Product.ASM_FEATURES,
      {} as ConfigurationDeserializer<Boolean>,
      listener)
    poller.removeListeners(Product.ASM_FEATURES)
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
    poller.removeListeners(Product._UNKNOWN)
    task.run(poller)

    then:
    0 * _._ // not even a request is made
  }

  void 'check setting of capabilities negative test'() {
    ConfigurationChangesTypedListener listener = Mock()

    when:
    poller.addListener(Product._UNKNOWN,
      {true } as ConfigurationDeserializer<Boolean>,
      listener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    0 * _._
    def body = parseBody(request.body())
    with(body.client) {
      capabilities[0] == 0
    }
  }

  private static String signAndBase64EncodeTargets(Map targets) {
    Map targetsSigned = targets['signed']
    if (targetsSigned) {
      byte[] canonicalTargetsSigned = JsonCanonicalizer.canonicalize(targetsSigned)
      Ed25519Signature signature = PRIVATE_KEY.expand().sign(canonicalTargetsSigned, PUBLIC_KEY)
      String sigBase16 = new BigInteger(1, signature.toByteArray()).toString(16)
      targets['signatures'] = [[
          keyid: KEY_ID,
          sig  : sigBase16,
        ]]
    }

    Base64.encoder.encodeToString(JsonOutput.toJson(targets).getBytes('UTF-8'))
  }

  private static String signAndBase64EncodeTargets(String targetsJson) {
    Map targets = SLURPER.parse(targetsJson.getBytes('UTF-8'))
    signAndBase64EncodeTargets(targets)
  }


  void 'check setting of capabilities positive test #capabilities'() {
    setup:
    ConfigurationDeserializer deserializer = { true } as ConfigurationDeserializer<Boolean>
    ConfigurationChangesTypedListener listener = { Object[] args -> } as ConfigurationChangesTypedListener

    when:
    poller.addListener(Product._UNKNOWN, deserializer, listener)
    poller.addCapabilities(capabilities)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(FEATURES_RESP_BODY) }
    0 * _._
    def body = parseBody(request.body())
    body.client.capabilities as byte[] == encoded

    where:
    capabilities          | encoded
    0                     | [0] as byte[]
    14L                   | [14] as byte[]
    1 << 8                | [1, 0] as byte[]
    1 << 9                | [2, 0] as byte[]
    -9223372036854775807L | [128, 0, 0, 0, 0, 0, 0, 1] as byte[]
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
   "targets" : "${signAndBase64EncodeTargets(SAMPLE_TARGETS)}"
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
  client_configs: ['datadog/2/ASM_FEATURES/asm_features_activation/config'],
  roots: [],
  target_files: [
    [
      path: 'datadog/2/ASM_FEATURES/asm_features_activation/config',
      raw: Base64.encoder.encodeToString('{"asm":{"enabled":true},"api_security":{"request_sample_rate":0.1}}'.getBytes('UTF-8'))
    ]
  ],
  targets: signAndBase64EncodeTargets(
  signed: [
    expires: '2022-09-17T12:49:15Z',
    spec_version: '1.0.0',
    targets: [
      'datadog/2/ASM_FEATURES/asm_features_activation/config': [
        custom: [
          v: 1
        ],
        hashes: [
          sha256: 'b01cb68f140fbfb7a2bb0ce39a473aa59c33664aca0c871cf07b8f4e09e3e360'
        ],
        length : 67,
      ]
    ],
    version: 23337393
  ]
  ))

  void 'POC: handles DEBUG product with SCA_ prefix'() {
    setup:
    def scaConfigContent = '{"enabled":true,"instrumentation_targets":[{"class_name":"com/fasterxml/jackson/databind/ObjectMapper","method_name":"readValue"}]}'
    def scaConfigKey = 'datadog/2/DEBUG/SCA_my_service_123/config'
    def scaConfigHash = String.format('%064x', new BigInteger(1, MessageDigest.getInstance('SHA-256').digest(scaConfigContent.getBytes('UTF-8'))))
    def respBody = JsonOutput.toJson(
      client_configs: [scaConfigKey],
      roots: [],
      target_files: [
        [
          path: scaConfigKey,
          raw: Base64.encoder.encodeToString(scaConfigContent.getBytes('UTF-8'))
        ]
      ],
      targets: signAndBase64EncodeTargets(
      signed: [
        expires: '2022-09-17T12:49:15Z',
        spec_version: '1.0.0',
        targets: [
          (scaConfigKey): [
            custom: [v: 1],
            hashes: [
              sha256: scaConfigHash
            ],
            length: scaConfigContent.size(),
          ]
        ],
        version: 1
      ]
      ))

    ConfigurationChangesTypedListener scaListener = Mock()

    when:
    poller.addListener(Product.DEBUG,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      scaListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }
    1 * scaListener.accept(scaConfigKey, _, _ as PollingRateHinter)
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }
    0 * _._

    def body = parseBody(request.body())
    with(body.client.state.config_states[0]) {
      id == 'SCA_my_service_123'
      product == 'DEBUG'
      version == 1
    }
  }

  void 'POC: DEBUG product without SCA_ prefix throws error'() {
    setup:
    def debugConfigKey = 'datadog/2/DEBUG/some_other_config/config'
    def respBody = JsonOutput.toJson(
      client_configs: [debugConfigKey],
      roots: [],
      target_files: [
        [
          path: debugConfigKey,
          raw: Base64.encoder.encodeToString('{"test":"data"}'.getBytes('UTF-8'))
        ]
      ],
      targets: signAndBase64EncodeTargets(
      signed: [
        expires: '2022-09-17T12:49:15Z',
        spec_version: '1.0.0',
        targets: [
          (debugConfigKey): [
            custom: [v: 1],
            hashes: [sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'],
            length: 15,
          ]
        ],
        version: 1
      ]
      ))

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      { Object[] args -> } as ConfigurationChangesTypedListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(SAMPLE_RESP_BODY) }
    0 * _._

    def body = parseBody(request.body())
    with(body.client.state) {
      has_error == true
      error == 'Told to handle config key datadog/2/DEBUG/some_other_config/config, but the product DEBUG is not being handled'
    }
  }

  void 'POC: multiple products including DEBUG with SCA_ are handled correctly'() {
    setup:
    def scaConfigContent = '{"enabled":true}'
    def scaConfigKey = 'datadog/2/DEBUG/SCA_service/config'
    def scaConfigHash = String.format('%064x', new BigInteger(1, MessageDigest.getInstance('SHA-256').digest(scaConfigContent.getBytes('UTF-8'))))
    def asmConfigKey = 'employee/ASM_DD/1.recommended.json/config'
    def respBody = JsonOutput.toJson(
      client_configs: [asmConfigKey, scaConfigKey],
      roots: [],
      target_files: [
        [
          path: asmConfigKey,
          raw: Base64.encoder.encodeToString(SAMPLE_APPSEC_CONFIG.getBytes('UTF-8'))
        ],
        [
          path: scaConfigKey,
          raw: Base64.encoder.encodeToString(scaConfigContent.getBytes('UTF-8'))
        ]
      ],
      targets: signAndBase64EncodeTargets(
      signed: [
        expires: '2022-09-17T12:49:15Z',
        spec_version: '1.0.0',
        targets: [
          (asmConfigKey): [
            custom: [v: 1],
            hashes: [sha256: '6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819'],
            length: 919,
          ],
          (scaConfigKey): [
            custom: [v: 1],
            hashes: [
              sha256: scaConfigHash
            ],
            length: scaConfigContent.size(),
          ]
        ],
        version: 1
      ]
      ))

    ConfigurationChangesTypedListener asmListener = Mock()
    ConfigurationChangesTypedListener scaListener = Mock()

    when:
    poller.addListener(Product.ASM_DD,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      asmListener)
    poller.addListener(Product.DEBUG,
      { SLURPER.parse(it) } as ConfigurationDeserializer,
      scaListener)
    poller.start()

    then:
    1 * scheduler.scheduleAtFixedRate(_, poller, 0, DEFAULT_POLL_PERIOD, TimeUnit.MILLISECONDS) >> { task = it[0]; scheduled }

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }
    1 * asmListener.accept(asmConfigKey, _, _ as PollingRateHinter)
    1 * scaListener.accept(scaConfigKey, _, _ as PollingRateHinter)
    0 * _._

    when:
    task.run(poller)

    then:
    1 * okHttpClient.newCall(_ as Request) >> { request = it[0]; call }
    1 * call.execute() >> { buildOKResponse(respBody) }
    0 * _._

    def body = parseBody(request.body())
    body.client.state.config_states.size() == 2
    def asmConfig = body.client.state.config_states.find { it.product == 'ASM_DD' }
    def scaConfig = body.client.state.config_states.find { it.product == 'DEBUG' }
    with(asmConfig) {
      id == '1.recommended.json'
      product == 'ASM_DD'
      version == 1
    }
    with(scaConfig) {
      id == 'SCA_service'
      product == 'DEBUG'
      version == 1
    }
  }
}
