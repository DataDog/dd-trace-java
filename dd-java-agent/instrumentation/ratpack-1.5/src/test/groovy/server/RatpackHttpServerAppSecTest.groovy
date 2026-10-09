package server

import datadog.trace.test.util.ExcludeInheritedFeatures
import groovy.json.JsonOutput
import okhttp3.MediaType
import okhttp3.RequestBody

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON

@ExcludeInheritedFeatures
class RatpackHttpServerAppSecTest extends RatpackHttpServerTest {

  /**
   * Blocks from the JSON response body callback only ('body' is ignored by responseHeaderDone),
   * reaching JsonRendererAdvice.
   */
  def 'test blocking on json response body'() {
    setup:
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
