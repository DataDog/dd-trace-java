package com.datadog.appsec.report

import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch
import datadog.communication.monitor.Counter
import datadog.communication.monitor.Monitoring
import datadog.trace.util.AgentTaskScheduler
import datadog.trace.util.AgentThreadFactory
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import spock.lang.Specification
import spock.util.concurrent.BlockingVariable

import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppSecApiSpecification extends Specification {
  private static final HttpUrl EXPECTED_ENDPOINT_URL = HttpUrl.get('http://example.com/appsec/v1/input')

  AgentTaskScheduler scheduler = new AgentTaskScheduler(AgentThreadFactory.AgentThread.APPSEC_HTTP_DISPATCHER)
  Monitoring monitoring = Mock()
  HttpUrl url = HttpUrl.get('http://example.com/')
  OkHttpClient okHttpClient = Mock()
  Counter counter = Mock()
  Call call = Mock()
  AppSecApi appSecApi
  Request savedRequest
  Response response
  def ready

  void 'sendIntakeBatch with success'() {
    when:
    appSecApi = new AppSecApi(monitoring, url, okHttpClient, scheduler)

    then:
    1 * monitoring.newCounter('appsec.batches.counter') >> counter

    when:
    ready = new BlockingVariable<Boolean>(0.5)
    appSecApi.sendIntakeBatch(new IntakeBatch(),
      ReportSerializer.intakeBatchAdapter)
    ready.get()

    then:
    1 * okHttpClient.newCall({ Request r ->
      r.method() == 'POST' && r.url() == EXPECTED_ENDPOINT_URL &&
        r.body().contentType() == MediaType.get("application/json")
    }) >> {
      savedRequest = it[0]
      response = new Response.Builder()
        .request(savedRequest)
        .protocol(Protocol.HTTP_1_0)
        .code(200)
        .message('OK')
        .build()
      call
    }
    1 * call.execute() >> { response }
    1 * counter.increment(1) >> { ready.set(true) }
  }

  void 'sendIntakeBatch with failure 404'() {
    when:
    appSecApi = new AppSecApi(monitoring, url, okHttpClient, scheduler)

    then:
    1 * monitoring.newCounter('appsec.batches.counter') >> counter

    when:
    ready = new BlockingVariable<Boolean>(0.5)
    appSecApi.sendIntakeBatch(new IntakeBatch(),
      ReportSerializer.intakeBatchAdapter)
    ready.get()

    then:
    1 * okHttpClient.newCall({ Request r ->
      r.method() == 'POST' && r.url() == EXPECTED_ENDPOINT_URL &&
        r.body().contentType() == MediaType.get("application/json")
    }) >> {
      savedRequest = it[0]
      def buffer = new Buffer()
      buffer.write("123".getBytes(Charset.forName('UTF-8')))
      response = new Response.Builder()
        .request(savedRequest)
        .protocol(Protocol.HTTP_1_0)
        .code(404)
        .message('Not Found')
        .body(new RealResponseBody('text/plain', 3, buffer))
        .build()
      call
    }
    1 * call.execute() >> { response }
    1 * counter.incrementErrorCount('404', 1) >> { ready.set(true) }
  }

  void 'sendIntakeBatch with failure IOException'() {
    when:
    appSecApi = new AppSecApi(monitoring, url, okHttpClient, scheduler)

    then:
    1 * monitoring.newCounter('appsec.batches.counter') >> counter

    when:
    ready = new BlockingVariable<Boolean>(0.5)
    appSecApi.sendIntakeBatch(new IntakeBatch(),
      ReportSerializer.intakeBatchAdapter)
    ready.get()

    then:
    1 * okHttpClient.newCall({ Request r ->
      r.method() == 'POST' && r.url() == EXPECTED_ENDPOINT_URL &&
        r.body().contentType() == MediaType.get("application/json")
    }) >> call
    1 * call.execute() >> {
      throw new IOException('foo')
    }
    1 * counter.incrementErrorCount('foo', 1) >> { ready.set(true) }
  }

  void 'only queues up to 5 tasks'() {
    when:
    appSecApi = new AppSecApi(monitoring, url, okHttpClient, scheduler)
    def processRequestLatch = new CountDownLatch(1)
    def fiveProcessedLatch = new CountDownLatch(5)

    then:
    1 * monitoring.newCounter('appsec.batches.counter') >> counter

    when:
    6.times {
      appSecApi.sendIntakeBatch(new IntakeBatch(),
        ReportSerializer.intakeBatchAdapter)
    }
    fiveProcessedLatch.await(1, TimeUnit.SECONDS)

    then:
    1 * counter.incrementErrorCount("Max queued requests reached", 1) >> {
      processRequestLatch.countDown()
    }
    5 * okHttpClient.newCall({ Request r ->
      r.method() == 'POST' && r.url() == EXPECTED_ENDPOINT_URL &&
        r.body().contentType() == MediaType.get("application/json")
    }) >> {
      processRequestLatch.await(1, TimeUnit.SECONDS)
      Call call = Mock()
      def savedRequest = it[0]
      response = new Response.Builder()
        .request(savedRequest)
        .protocol(Protocol.HTTP_1_0)
        .code(200)
        .message('OK')
        .build()
      call.execute() >> response
      call
    }
    5 * counter.increment(1) >> { fiveProcessedLatch.countDown() }
  }
}
