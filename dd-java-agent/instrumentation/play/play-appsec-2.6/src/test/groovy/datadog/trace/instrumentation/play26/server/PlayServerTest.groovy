package datadog.trace.instrumentation.play26.server

import okhttp3.MediaType
import okhttp3.RequestBody

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_XML

class PlayServerTest extends AbstractPlayServerTest {

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
