package datadog.trace.instrumentation.play25.server

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.play25.PlayRoutersScala
import datadog.trace.test.util.ExcludeInheritedFeatures
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import okhttp3.MediaType
import okhttp3.RequestBody
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static org.junit.jupiter.api.Assumptions.assumeTrue

@ExcludeInheritedFeatures
class PlayServerAppSecTest extends PlayServerTest {

  /**
   * Blocks from the JSON response body callback only ('body' is ignored by responseHeaderDone),
   * reaching StatusHeaderSendJsonAdvice (Java routers) or ResultsStatusApplyAdvice (Scala routers).
   */
  def 'test blocking on json response body'() {
    setup:
    assumeTrue(testBlockingOnResponse() && testResponseBodyJson())
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(MediaType.get('application/json'), JsonOutput.toJson([a: 'x'])))
      .header(IG_BLOCK_RESPONSE_HEADER, 'body')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 413
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)
    def rootSpan = TEST_WRITER.get(0).find {
      it.parentId == 0
    }
    rootSpan != null
    rootSpan.tags['http.status_code'] == 413
    rootSpan.tags['appsec.blocked'] == 'true'
  }
}

class PlayScalaAsyncServerAppSecTest extends PlayServerAppSecTest {
  @Shared
  ExecutorService executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  @CompileStatic
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    new PlayHttpServer(PlayRoutersScala.async(executor).asJava())
  }
}
