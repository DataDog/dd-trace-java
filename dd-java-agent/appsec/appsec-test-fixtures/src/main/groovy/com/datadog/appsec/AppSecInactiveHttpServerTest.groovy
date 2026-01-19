package com.datadog.appsec

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.metrics.api.Monitoring
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.api.Config
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.DDSpan
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.Request

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static org.junit.jupiter.api.Assumptions.assumeTrue

abstract class AppSecInactiveHttpServerTest extends WithHttpServer {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.appsec.enabled', '')
    injectSysConfig('dd.remote_config.enabled', 'false')
  }

  @Override
  protected boolean isForceAppSecActive() {
    false
  }

  void setupSpec() {
    SubscriptionService ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
    def sco = new SharedCommunicationObjects()
    def config = Config.get()
    sco.createRemaining(config)
    assert sco.configurationPoller(config) == null
    assert sco.monitoring instanceof Monitoring.DisabledMonitoring

    AppSecSystem.start(ss, sco)
    assert !AppSecSystem.active
  }

  void cleanupSec() {
    AppSecSystem.stop()
  }

  DDSpan getTopSpan() {
    TEST_WRITER.get(0).sort (false, {it.spanId }).first()
  }

  protected buildUrl(ServerEndpoint endpoint) {
    HttpUrl.get(address.resolve(endpoint.relativePath()))
      .newBuilder()
      .query(endpoint.query)
      .build()
  }

  boolean isTestPathParam() {
    false
  }
  boolean isTestMatrixParam() {
    false
  }

  // tests follow

  void 'test get #endpoint'() {
    setup:
    if (assume) {
      assumeTrue assume()
    }
    def request = new Request.Builder().url(buildUrl(endpoint))
      .header('x-forwarded-for', '1.2.3.4')
      .method('GET', null).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == endpoint.status
    if (endpoint != REDIRECT) {
      response.body().string() == endpoint.body
    } else {
      response.headers().get('location').contains('/redirected')
    }

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    with(topSpan) {
      getTag('_dd.appsec.enabled') == null
    }

    where:
    endpoint     | assume
    SUCCESS      | null
    PATH_PARAM   | { -> testPathParam }
    MATRIX_PARAM | { -> testMatrixParam }
    QUERY_PARAM  | null
    FORWARDED    | null
    REDIRECT     | null
  }

  void urlencoded() {
    setup:
    def body = new FormBody.Builder()
      .add('a', 'x')
      .build()
    def request = new Request.Builder()
      .url(buildUrl(BODY_URLENCODED))
      .method('POST', body).build()
    def response = client.newCall(request).execute()
    if (dataStreamsEnabled) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == BODY_URLENCODED.status
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    with(topSpan) {
      getTag('_dd.appsec.enabled') == null
    }
  }

  void multipart() {
    setup:
    def body = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('a', 'x')
      .build()
    def request = new Request.Builder()
      .url(buildUrl(BODY_MULTIPART))
      .method('POST', body).build()
    def response = client.newCall(request).execute()
    if (dataStreamsEnabled) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == BODY_MULTIPART.status
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    with(topSpan) {
      getTag('_dd.appsec.enabled') == null
    }
  }

  // dummy implementation of datadog.trace.agent.test.naming.VersionedNamingTest
  @Override
  int version() {
    0
  }

  @Override
  String service() {
    null
  }

  @Override
  String operation() {
    null
  }
}
