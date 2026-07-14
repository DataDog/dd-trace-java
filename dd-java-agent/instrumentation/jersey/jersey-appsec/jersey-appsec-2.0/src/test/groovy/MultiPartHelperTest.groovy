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
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'field'
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream('value'.bytes)
    bodyPart.getFormDataContentDisposition() >> null
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
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'tag'
    bodyPart.getEntityAs(InputStream) >>> [new ByteArrayInputStream('a'.bytes), new ByteArrayInputStream('b'.bytes)]
    bodyPart.getFormDataContentDisposition() >> null
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
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'field'
    bodyPart.getEntityAs(InputStream) >> new ByteArrayInputStream(longValue.bytes)
    bodyPart.getFormDataContentDisposition() >> null
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null, null)

    then:
    map['field'][0] == 'a' * MultiPartHelper.MAX_CONTENT_BYTES
  }

  def "MAX_FILES_TO_INSPECT limits number of distinct body map field names"() {
    given:
    def parts = (1..MultiPartHelper.MAX_FILES_TO_INSPECT + 2).collect { i ->
      def bp = Mock(FormDataBodyPart)
      bp.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
      bp.getName() >> "field${i}"
      bp.getEntityAs(InputStream) >> new ByteArrayInputStream("value${i}".bytes)
      bp.getFormDataContentDisposition() >> null
      bp
    }
    def map = [:]

    when:
    parts.each { MultiPartHelper.collectBodyPart(it, map, null, null) }

    then:
    map.size() == MultiPartHelper.MAX_FILES_TO_INSPECT
  }

  def "values for an existing field name accumulate even when the field-name cap is reached"() {
    given: "the map filled up to the cap with distinct field names"
    def existing = (1..MultiPartHelper.MAX_FILES_TO_INSPECT).collect { i ->
      def bp = Mock(FormDataBodyPart)
      bp.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
      bp.getName() >> "field${i}"
      bp.getEntityAs(InputStream) >> { new ByteArrayInputStream("value${i}".bytes) }
      bp.getFormDataContentDisposition() >> null
      bp
    }
    def map = [:]
    existing.each { MultiPartHelper.collectBodyPart(it, map, null, null) }

    and: "a body part reusing an already-collected field name"
    def repeat = Mock(FormDataBodyPart)
    repeat.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    repeat.getName() >> 'field1'
    repeat.getEntityAs(InputStream) >> new ByteArrayInputStream('extra'.bytes)
    repeat.getFormDataContentDisposition() >> null

    and: "a body part with a brand-new field name"
    def fresh = Mock(FormDataBodyPart)
    fresh.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    fresh.getName() >> 'brandNew'
    fresh.getEntityAs(InputStream) >> new ByteArrayInputStream('nope'.bytes)
    fresh.getFormDataContentDisposition() >> null

    when:
    MultiPartHelper.collectBodyPart(repeat, map, null, null)
    MultiPartHelper.collectBodyPart(fresh, map, null, null)

    then: "the existing field accumulates the new value"
    map['field1'] == ['value1', 'extra']

    and: "the cap gates new field names, so the map does not grow and the new name is rejected"
    map.size() == MultiPartHelper.MAX_FILES_TO_INSPECT
    !map.containsKey('brandNew')
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
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'file'
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
