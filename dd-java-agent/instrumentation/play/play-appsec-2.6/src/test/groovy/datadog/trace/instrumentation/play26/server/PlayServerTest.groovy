package datadog.trace.instrumentation.play26.server

import groovy.json.JsonOutput
import okhttp3.MediaType
import okhttp3.RequestBody

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_XML

class PlayServerTest extends AbstractPlayServerTest {

  @Override
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyFilesContent() {
    true
  }

  /**
   * Blocks from the JSON response body callback only ('body' is ignored by responseHeaderDone),
   * reaching StatusHeaderSendJsonAdvice.
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

  def 'test instrumentation gateway xml request body'() {
    setup:
    def request = request(
      BODY_XML, 'POST',
      RequestBody.create(MediaType.get('text/xml'), '<foo attr="attr_value">mytext<bar></bar></foo>'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    String body = response.body().charStream().text


    expect:
    body == BODY_XML.body || body == '<?xml version="1.0" encoding="UTF-8"?><foo attr="attr_value">mytext<bar/></foo>'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[[children:[mytext, [:]], attributes:[attr:attr_value]]]'
    }
  }
}
