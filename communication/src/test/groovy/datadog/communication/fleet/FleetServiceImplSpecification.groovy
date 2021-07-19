package datadog.communication.fleet

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.util.AgentThreadFactory
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import spock.lang.Specification

import java.util.concurrent.CountDownLatch

import static java.util.concurrent.TimeUnit.SECONDS

class FleetServiceImplSpecification extends Specification{
  private static final HttpUrl EXPECTED_URL = HttpUrl.get('http://example.com/v0.6/config')
  OkHttpClient okHttpClient = Mock()
  HttpUrl url = HttpUrl.get('http://example.com/')

  FleetServiceImpl fleetService = new FleetServiceImpl(
  new SharedCommunicationObjects(okHttpClient: okHttpClient, agentUrl: url),
  new AgentThreadFactory(AgentThreadFactory.AgentThread.FLEET_MANAGEMENT_POLLER)
  )

  void cleanup() {
    fleetService.close()
  }

  void 'success response is distributed'() {
    FleetService.ConfigurationListener listener = Mock()
    fleetService.subscribe(FleetService.Product.APPSEC, listener)
    fleetService.testingLatch = new CountDownLatch(2)

    when:
    fleetService.init()
    fleetService.testingLatch.await(5, SECONDS)

    then:
    1 * okHttpClient.newCall(
      { it.url() == EXPECTED_URL && it.method() == 'GET'}) >> {
        Request req = it[0]
        Call call = Mock()
        Response response = createResponse(req)
        1 * call.execute() >> response
        call
      }
    1 * listener.onNewConfiguration({ it.text == '{}' })
  }

  void 'success response is distributed even if there is an unsuccessful one'() {
    FleetService.ConfigurationListener listener1 = Mock(),
    listener2 = Mock()
    fleetService.subscribe(FleetService.Product.APPSEC, listener1)
    fleetService.subscribe(FleetService.Product.RUNTIME_SECURITY, listener2)
    fleetService.testingLatch = new CountDownLatch(2)

    when:
    fleetService.init()
    fleetService.testingLatch.await(5, SECONDS)

    then:
    1 * okHttpClient.newCall( { Request it ->
      it.url() == EXPECTED_URL && it.method() == 'GET' &&
        it.header('Datadog-Client-Config-Product') == 'APPSEC'
    }) >> {
      Request req = it[0]
      Call call = Mock()
      1 * call.execute() >> { throw new IOException('test exception') }
      call
    }
    1 * okHttpClient.newCall( { Request it ->
      it.url() == EXPECTED_URL && it.method() == 'GET' &&
        it.header('Datadog-Client-Config-Product') == 'RUNTIME_SECURITY'
    }) >> {
      Request req = it[0]
      Call call = Mock()
      1 * call.execute() >> createResponse(req)
      call
    }

    0 * listener1._(*_)
    1 * listener2.onNewConfiguration({ it.text == '{}' })
  }

  void 'unchanged data is not republished'() {
    FleetService.ConfigurationListener listener = Mock()
    fleetService.subscribe(FleetService.Product.APPSEC, listener)
    fleetService.testingLatch = new CountDownLatch(3)

    when:
    fleetService.init()
    fleetService.testingLatch.await(5, SECONDS)

    then:
    2 * okHttpClient.newCall(
      { it.url() == EXPECTED_URL && it.method() == 'GET'}) >> {
        Request req = it[0]
        Call call = Mock()
        Response response = createResponse(req)
        1 * call.execute() >> response
        call
      }
    1 * listener.onNewConfiguration({ it.text == '{}' })
  }

  void 'non 200 response is deemed an error'() {
    FleetService.ConfigurationListener listener = Mock()
    fleetService.subscribe(FleetService.Product.APPSEC, listener)
    fleetService.testingLatch = new CountDownLatch(2)

    when:
    fleetService.init()
    fleetService.testingLatch.await(5, SECONDS)

    then:
    1 * okHttpClient.newCall(
      { it.url() == EXPECTED_URL && it.method() == 'GET'}) >> {
        Request req = it[0]
        Call call = Mock()
        Response response = createResponse(req, [code: 404])
        1 * call.execute() >> response
        call
      }
    0 * listener._(*_)
  }

  void 'subscriptions can be canceled'() {
    FleetService.ConfigurationListener listener = Mock()
    FleetService.FleetSubscription sub =
      fleetService.subscribe(FleetService.Product.APPSEC, listener)
    fleetService.testingLatch = new CountDownLatch(2)

    when:
    sub.cancel()
    fleetService.init()
    fleetService.testingLatch.await(5, SECONDS)

    then:
    0 * listener._(*_)
  }

  private Response createResponse(Request req, Map params = [:]) {
    new Response.Builder()
      .request(req)
      .code(params.get('code', 200))
      .message('OK')
      .protocol(Protocol.HTTP_1_0)
      .body(ResponseBody.create(MediaType.get("application/json"), params.get('body', '{}')))
      .build()
  }
}
