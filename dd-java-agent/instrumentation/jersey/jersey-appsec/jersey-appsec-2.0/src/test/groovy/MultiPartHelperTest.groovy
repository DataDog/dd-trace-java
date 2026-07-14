import datadog.appsec.api.blocking.BlockingException
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.instrumentation.jersey2.MultiPartHelper
import org.glassfish.jersey.media.multipart.FormDataBodyPart
import org.glassfish.jersey.media.multipart.FormDataContentDisposition
import spock.lang.Specification

import javax.ws.rs.core.MediaType

class MultiPartHelperTest extends Specification {

  // collectBodyPart — body map

  def "text/plain part is added to body map"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getName() >> 'field'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream('value'.bytes)
    bodyPart.getFormDataContentDisposition() >> cd
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)

    then:
    map == [field: ['value']]
  }

  def "non-text/plain part is not added to body map"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> null
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)

    then:
    map.isEmpty()
  }

  def "null body map is skipped without error"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getFormDataContentDisposition() >> null

    expect:
    MultiPartHelper.collectBodyPart(bodyPart, null, null, null)
  }

  def "multiple values for same field are accumulated"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getName() >> 'tag'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getEntityAs(InputStream) >>> [new ByteArrayInputStream('a'.bytes), new ByteArrayInputStream('b'.bytes)]
    bodyPart.getFormDataContentDisposition() >> cd
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)

    then:
    map == [tag: ['a', 'b']]
  }

  def "text/plain field value longer than MAX_CONTENT_BYTES is truncated"() {
    given:
    def longValue = 'a' * (MultiPartHelper.MAX_CONTENT_BYTES + 100)
    def cd = Mock(FormDataContentDisposition)
    cd.getName() >> 'field'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream(longValue.bytes)
    bodyPart.getFormDataContentDisposition() >> cd
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)

    then:
    map['field'][0] == 'a' * MultiPartHelper.MAX_CONTENT_BYTES
  }

  def "MAX_FILES_TO_INSPECT limits number of distinct body map field names"() {
    given:
    def parts = (1..MultiPartHelper.MAX_FILES_TO_INSPECT + 2).collect { i ->
      def cd = Mock(FormDataContentDisposition)
      cd.getName() >> "field${i}"
      def bp = Mock(FormDataBodyPart)
      bp.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
      bp.getEntityAs(InputStream) >> new ByteArrayInputStream("value${i}".bytes)
      bp.getFormDataContentDisposition() >> cd
      bp
    }
    def map = [:]

    when:
    parts.each { MultiPartHelper.collectBodyPart(it, map, null, null) }

    then:
    map.size() == MultiPartHelper.MAX_FILES_TO_INSPECT
  }

  def "MAX_FILES_TO_INSPECT limits total accumulated values, even for a repeated field name"() {
    given: "the map filled up to the cap with distinct field names"
    def existing = (1..MultiPartHelper.MAX_FILES_TO_INSPECT).collect { i ->
      def cd = Mock(FormDataContentDisposition)
      cd.getName() >> "field${i}"
      def bp = Mock(FormDataBodyPart)
      bp.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
      bp.getEntityAs(InputStream) >> { new ByteArrayInputStream("value${i}".bytes) }
      bp.getFormDataContentDisposition() >> cd
      bp
    }
    def map = [:]
    existing.each { MultiPartHelper.collectBodyPart(it, map, null, null) }

    and: "a body part reusing an already-collected field name"
    def repeatCd = Mock(FormDataContentDisposition)
    repeatCd.getName() >> 'field1'
    def repeat = Mock(FormDataBodyPart)
    repeat.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    repeat.getEntityAs(InputStream) >> new ByteArrayInputStream('extra'.bytes)
    repeat.getFormDataContentDisposition() >> repeatCd

    and: "a body part with a brand-new field name"
    def freshCd = Mock(FormDataContentDisposition)
    freshCd.getName() >> 'brandNew'
    def fresh = Mock(FormDataBodyPart)
    fresh.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    fresh.getEntityAs(InputStream) >> new ByteArrayInputStream('nope'.bytes)
    fresh.getFormDataContentDisposition() >> freshCd

    when:
    MultiPartHelper.collectBodyPart(repeat, map, null, null)
    MultiPartHelper.collectBodyPart(fresh, map, null, null)

    then: "the total value cap rejects both the repeated field's extra value and the brand-new field"
    map['field1'] == ['value1']
    map.size() == MultiPartHelper.MAX_FILES_TO_INSPECT
    !map.containsKey('brandNew')
  }

  def "malformed Content-Disposition on a text/plain part skips body map gracefully"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getFormDataContentDisposition() >> { throw new IllegalArgumentException("bad CD") }
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)

    then:
    map.isEmpty()
    noExceptionThrown()
  }

  // collectBodyPart — filenames

  def "filename is added to list when present"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> 'report.php'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> cd
    def filenames = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, null, filenames, null)

    then:
    filenames == ['report.php']
  }

  def "null filenames list is skipped without error"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> 'report.php'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'f'
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream('v'.bytes)
    bodyPart.getFormDataContentDisposition() >> cd

    expect:
    MultiPartHelper.collectBodyPart(bodyPart, [:], null, null)
  }

  def "text/plain part with filename populates both body map and filename list"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> 'upload.txt'
    cd.getName() >> 'file'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream('content'.bytes)
    bodyPart.getFormDataContentDisposition() >> cd
    def map = [:]
    def filenames = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, filenames, null)

    then:
    map == [file: ['content']]
    filenames == ['upload.txt']
  }

  def "empty filename adds to content but not filenames"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> ''
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> cd
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream('data'.bytes)
    def filenames = []
    def content = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, null, filenames, content)

    then:
    filenames.isEmpty()
    content == ['data']
  }

  def "null filename (no filename attr) skips both filenames and content"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> null
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> cd
    def filenames = []
    def content = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, null, filenames, content)

    then:
    filenames.isEmpty()
    content.isEmpty()
  }

  def "non-empty filename adds to both filenames and content"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> 'report.php'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> cd
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream('<?php echo 1;'.bytes)
    def filenames = []
    def content = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, null, filenames, content)

    then:
    filenames == ['report.php']
    content == ['<?php echo 1;']
  }

  def "getFormDataContentDisposition throw skips filenames and content gracefully"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> { throw new IllegalArgumentException("bad CD") }
    def filenames = []
    def content = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, null, filenames, content)

    then:
    filenames.isEmpty()
    content.isEmpty()
    noExceptionThrown()
  }

  def "MAX_FILES_TO_INSPECT limits number of content entries"() {
    given:
    def parts = (1..MultiPartHelper.MAX_FILES_TO_INSPECT + 2).collect { i ->
      def cd = Mock(FormDataContentDisposition)
      cd.getFileName() >> "f${i}.bin"
      def bp = Mock(FormDataBodyPart)
      bp.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
      bp.getFormDataContentDisposition() >> cd
      bp.getEntityAs(InputStream) >> new ByteArrayInputStream("content${i}".bytes)
      bp
    }
    def content = []

    when:
    parts.each { MultiPartHelper.collectBodyPart(it, null, null, content) }

    then:
    content.size() == MultiPartHelper.MAX_FILES_TO_INSPECT
  }

  // tryBlock

  def "tryBlock returns null when flow action is not a blocking action"() {
    given:
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> Flow.Action.Noop.INSTANCE
    RequestContext ctx = Stub(RequestContext)

    expect:
    MultiPartHelper.tryBlock(ctx, flow, 'msg') == null
  }

  def "tryBlock returns BlockingException with provided message when brf commits response"() {
    given:
    def segment = Stub(TraceSegment)
    def rba = Stub(Flow.Action.RequestBlockingAction)
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> rba
    BlockResponseFunction brf = Stub(BlockResponseFunction)
    RequestContext ctx = Stub(RequestContext)
    ctx.getBlockResponseFunction() >> brf
    ctx.getTraceSegment() >> segment

    when:
    def result = MultiPartHelper.tryBlock(ctx, flow, 'blocked!')

    then:
    result instanceof BlockingException
    result.message == 'blocked!'
  }

  def "tryBlock calls tryCommitBlockingResponse and effectivelyBlocked"() {
    given:
    def segment = Mock(TraceSegment)
    def rba = Stub(Flow.Action.RequestBlockingAction)
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> rba
    BlockResponseFunction brf = Mock(BlockResponseFunction)
    RequestContext ctx = Stub(RequestContext)
    ctx.getBlockResponseFunction() >> brf
    ctx.getTraceSegment() >> segment

    when:
    MultiPartHelper.tryBlock(ctx, flow, 'msg')

    then:
    1 * brf.tryCommitBlockingResponse(segment, rba)
    1 * segment.effectivelyBlocked()
  }

  def "tryBlock returns null when brf is null despite blocking action"() {
    given:
    def rba = Stub(Flow.Action.RequestBlockingAction)
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> rba
    RequestContext ctx = Stub(RequestContext)
    ctx.getBlockResponseFunction() >> null

    expect:
    MultiPartHelper.tryBlock(ctx, flow, 'msg') == null
  }
}
