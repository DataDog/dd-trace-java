import datadog.appsec.api.blocking.BlockingException
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.instrumentation.resteasy.MultipartHelper
import org.jboss.resteasy.plugins.providers.multipart.InputPart
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput
import org.jboss.resteasy.specimpl.MultivaluedMapImpl
import spock.lang.Specification

import java.lang.reflect.Method

class MultipartHelperTest extends Specification {

  // rawFilenameFromContentDisposition (package-private, tested via reflection)

  private static String rawFilename(String cd) {
    Method m = MultipartHelper.getDeclaredMethod('rawFilenameFromContentDisposition', String)
    m.setAccessible(true)
    return (String) m.invoke(null, [cd] as Object[])
  }

  def "rawFilenameFromContentDisposition returns null when filename attr absent"() {
    expect:
    rawFilename(cd) == null

    where:
    cd << [null, 'form-data', 'form-data; name="field"', '']
  }

  def "rawFilenameFromContentDisposition returns empty string for filename with empty value"() {
    expect:
    rawFilename('form-data; filename=""') == ''
    rawFilename('form-data; filename=') == ''
  }

  def "rawFilenameFromContentDisposition returns the value for non-empty filename"() {
    expect:
    rawFilename('form-data; filename="report.php"') == 'report.php'
    rawFilename('form-data; filename=report.php') == 'report.php'
  }

  // filenameFromContentDisposition (public API)

  def "returns null when no filename parameter"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == null

    where:
    cd << [
      null,
      'form-data',
      'form-data; name="field"',
      'form-data; name="field"; other=value',
      '',
    ]
  }

  def "extracts unquoted filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                          | expected
    'form-data; filename=report.php'            | 'report.php'
    'form-data; name="f"; filename=upload.txt'  | 'upload.txt'
    'attachment; filename=file.tar.gz'          | 'file.tar.gz'
  }

  def "extracts quoted filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                             | expected
    'form-data; filename="report.php"'             | 'report.php'
    'form-data; name="f"; filename="upload.txt"'   | 'upload.txt'
  }

  def "handles semicolons inside quoted filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                               | expected
    'form-data; filename="report;.php"'              | 'report;.php'
    'form-data; name="f"; filename="a;b;c.php"'      | 'a;b;c.php'
    'form-data; filename="shell;evil.php"'            | 'shell;evil.php'
  }

  def "handles escaped quotes inside filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition('form-data; filename="file\\"name.php"') == 'file"name.php'
  }

  def "returns null for empty filename value"() {
    expect:
    MultipartHelper.filenameFromContentDisposition('form-data; filename=""') == null
    MultipartHelper.filenameFromContentDisposition('form-data; filename=') == null
  }

  def "is case-insensitive for the filename parameter name"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == 'report.php'

    where:
    cd << [
      'form-data; FILENAME="report.php"',
      'form-data; Filename="report.php"',
      'form-data; fileName="report.php"',
    ]
  }

  def "handles MIME linear whitespace (tab) after semicolon"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                                        | expected
    'form-data; name="f";\tfilename="evil.php"'              | 'evil.php'
    'form-data;\tfilename="evil.php"'                        | 'evil.php'
    'form-data; name="f";\t\tfilename="evil.php"'            | 'evil.php'
  }

  def "handles optional whitespace around the equals sign"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                                        | expected
    'form-data; filename ="report.php"'                      | 'report.php'
    'form-data; filename= "report.php"'                      | 'report.php'
    'form-data; filename = "report.php"'                     | 'report.php'
    'form-data; filename\t=\t"report.php"'                   | 'report.php'
    'form-data; name="f";\tfilename\t=\t"evil.php"'          | 'evil.php'
  }

  def "does not match filename* extended parameter as filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition("form-data; filename*=UTF-8''evil.php") == null
  }

  // header fixtures

  private static MultivaluedMapImpl<String, String> headers(String cd) {
    def h = new MultivaluedMapImpl<String, String>()
    h.add('Content-Disposition', cd)
    h
  }

  private static MultivaluedMapImpl<String, String> headers(String cd, String contentType) {
    def h = headers(cd)
    h.add('Content-Type', contentType)
    h
  }

  // collectBodyMap

  def "collectBodyMap collects text parts grouped by field name"() {
    given:
    def p1 = Mock(InputPart)
    p1.getHeaders() >> headers('form-data; name="field"')
    p1.getBody(_, _) >> new ByteArrayInputStream('value'.bytes)
    def p2 = Mock(InputPart)
    p2.getHeaders() >> headers('form-data; name="tag"')
    p2.getBody(_, _) >> new ByteArrayInputStream('a'.bytes)
    def p3 = Mock(InputPart)
    p3.getHeaders() >> headers('form-data; name="tag"')
    p3.getBody(_, _) >> new ByteArrayInputStream('b'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [p1], 'tag': [p2, p3]]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then:
    result == ['field': ['value'], 'tag': ['a', 'b']]
  }

  def "collectBodyMap returns an empty map when there are no parts"() {
    given:
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> [:]

    expect:
    MultipartHelper.collectBodyMap(ret).isEmpty()
  }

  def "collectBodyMap decodes using the part declared charset"() {
    given:
    def part = Mock(InputPart)
    part.getHeaders() >> headers('form-data; name="field"', 'text/plain; charset=ISO-8859-1')
    part.getBody(_, _) >> new ByteArrayInputStream('café'.getBytes('ISO-8859-1'))
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [part]]

    expect:
    MultipartHelper.collectBodyMap(ret) == ['field': ['café']]
  }

  def "collectBodyMap truncates a value longer than MAX_CONTENT_BYTES"() {
    given:
    def longValue = 'a' * (MultipartHelper.MAX_CONTENT_BYTES + 100)
    def part = Mock(InputPart)
    part.getHeaders() >> headers('form-data; name="field"')
    part.getBody(_, _) >> new ByteArrayInputStream(longValue.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [part]]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then:
    result['field'][0] == 'a' * MultipartHelper.MAX_CONTENT_BYTES
  }

  def "collectBodyMap caps total values across distinct field names"() {
    given:
    def entries = [:]
    (1..MultipartHelper.MAX_FILES_TO_INSPECT + 3).each { i ->
      def p = Mock(InputPart)
      p.getHeaders() >> headers("form-data; name=\"field${i}\"")
      p.getBody(_, _) >> new ByteArrayInputStream("value${i}".bytes)
      entries["field${i}".toString()] = [p]
    }
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> entries

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then:
    result.values().sum { it.size() } == MultipartHelper.MAX_FILES_TO_INSPECT
  }

  def "collectBodyMap caps total values even when every part reuses the same field name"() {
    given: "all parts share a single field name, so the map only ever has one key"
    def parts = (1..MultipartHelper.MAX_FILES_TO_INSPECT + 5).collect { i ->
      def p = Mock(InputPart)
      p.getHeaders() >> headers('form-data; name="same"')
      p.getBody(_, _) >> new ByteArrayInputStream("value${i}".bytes)
      p
    }
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['same': parts]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then: "the cap counts total values, not distinct keys"
    result.size() == 1
    result['same'].size() == MultipartHelper.MAX_FILES_TO_INSPECT
  }

  def "collectBodyMap keeps accumulating values for an already present field below the cap"() {
    given:
    def parts = (1..3).collect { i ->
      def p = Mock(InputPart)
      p.getHeaders() >> headers('form-data; name="tag"')
      p.getBody(_, _) >> new ByteArrayInputStream("v${i}".bytes)
      p
    }
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['tag': parts]

    expect:
    MultipartHelper.collectBodyMap(ret) == ['tag': ['v1', 'v2', 'v3']]
  }

  def "collectBodyMap maps a part to an empty string when reading it fails"() {
    given:
    def failing = Mock(InputPart)
    failing.getHeaders() >> headers('form-data; name="bad"')
    failing.getBody(_, _) >> { throw new IOException('boom') }
    def ok = Mock(InputPart)
    ok.getHeaders() >> headers('form-data; name="good"')
    ok.getBody(_, _) >> new ByteArrayInputStream('fine'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['bad': [failing], 'good': [ok]]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then:
    result == ['bad': [''], 'good': ['fine']]
    noExceptionThrown()
  }

  def "collectBodyMap maps a part to an empty string when reading it throws an unchecked exception"() {
    given:
    def failing = Mock(InputPart)
    failing.getHeaders() >> headers('form-data; name="bad"')
    failing.getBody(_, _) >> { throw new RuntimeException('no MessageBodyReader') }
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['bad': [failing]]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then:
    result == ['bad': ['']]
    noExceptionThrown()
  }

  def "collectBodyMap treats a part whose getHeaders() throws as having no content-type, without aborting"() {
    given:
    def broken = Mock(InputPart)
    broken.getHeaders() >> { throw new IllegalStateException('boom') }
    broken.getBody(_, _) >> new ByteArrayInputStream('value'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [broken]]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then:
    result == ['field': ['value']]
    noExceptionThrown()
  }

  def "collectBodyMap treats a part whose getHeaders() returns null as having no content-type"() {
    given:
    def part = Mock(InputPart)
    part.getHeaders() >> null
    part.getBody(_, _) >> new ByteArrayInputStream('value'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [part]]

    expect:
    MultipartHelper.collectBodyMap(ret) == ['field': ['value']]
  }

  def "collectBodyMap decodes an undeclared-charset value as UTF-8, not the JVM platform default"() {
    given:
    def part = Mock(InputPart)
    part.getHeaders() >> headers('form-data; name="field"')
    part.getBody(_, _) >> new ByteArrayInputStream('café'.getBytes('UTF-8'))
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [part]]

    expect:
    MultipartHelper.collectBodyMap(ret) == ['field': ['café']]
  }

  def "collectBodyMap excludes a non-text/plain part and does not consume the cap for it"() {
    given: "a file part and a text part sharing the same request"
    def file = Mock(InputPart)
    file.getHeaders() >> headers('form-data; name="upload"; filename="a.bin"', 'application/octet-stream')
    def text = Mock(InputPart)
    text.getHeaders() >> headers('form-data; name="q"')
    text.getBody(_, _) >> new ByteArrayInputStream('<script>'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['upload': [file], 'q': [text]]

    when:
    def result = MultipartHelper.collectBodyMap(ret)

    then: "the file part never reaches getBody() and is absent from the map"
    result == ['q': ['<script>']]
    0 * file.getBody(_, _)
  }

  def "collectBodyMap does not let dummy file parts exhaust the cap before a real text field"() {
    given: "MAX_FILES_TO_INSPECT dummy file parts followed by one real text field"
    def entries = [:]
    (1..MultipartHelper.MAX_FILES_TO_INSPECT).each { i ->
      def p = Mock(InputPart)
      p.getHeaders() >> headers("form-data; name=\"file${i}\"; filename=\"f${i}.bin\"", 'application/octet-stream')
      entries["file${i}".toString()] = [p]
    }
    def q = Mock(InputPart)
    q.getHeaders() >> headers('form-data; name="q"')
    q.getBody(_, _) >> new ByteArrayInputStream('payload'.bytes)
    entries['q'] = [q]
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> entries

    expect:
    MultipartHelper.collectBodyMap(ret) == ['q': ['payload']]
  }

  // collectFilesContent

  def "collectFilesContent includes part with non-empty filename"() {
    given:
    def part = Mock(InputPart)
    part.getHeaders() >> headers('form-data; name="file"; filename="report.php"')
    part.getBody(_, _) >> new ByteArrayInputStream('malicious'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['file': [part]]

    when:
    def result = MultipartHelper.collectFilesContent(ret)

    then:
    result == ['malicious']
  }

  def "collectFilesContent includes part with empty filename (security fix)"() {
    given:
    def part = Mock(InputPart)
    part.getHeaders() >> headers('form-data; name="file"; filename=""')
    part.getBody(_, _) >> new ByteArrayInputStream('anonymous'.bytes)
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['file': [part]]

    when:
    def result = MultipartHelper.collectFilesContent(ret)

    then:
    result == ['anonymous']
  }

  def "collectFilesContent skips part without filename attribute"() {
    given:
    def part = Mock(InputPart)
    part.getHeaders() >> headers('form-data; name="field"')
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['field': [part]]

    when:
    def result = MultipartHelper.collectFilesContent(ret)

    then:
    result.isEmpty()
  }

  def "collectFilesContent respects MAX_FILES_TO_INSPECT limit"() {
    given:
    def parts = (1..MultipartHelper.MAX_FILES_TO_INSPECT + 2).collect { i ->
      def p = Mock(InputPart)
      p.getHeaders() >> headers("form-data; name=\"f${i}\"; filename=\"f${i}.bin\"")
      p.getBody(_, _) >> new ByteArrayInputStream("content${i}".bytes)
      p
    }
    def ret = Mock(MultipartFormDataInput)
    ret.getFormDataMap() >> ['files': parts]

    when:
    def result = MultipartHelper.collectFilesContent(ret)

    then:
    result.size() == MultipartHelper.MAX_FILES_TO_INSPECT
  }

  // tryBlock

  def "tryBlock returns null when flow action is not a blocking action"() {
    given:
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> Flow.Action.Noop.INSTANCE
    RequestContext ctx = Stub(RequestContext)

    expect:
    MultipartHelper.tryBlock(ctx, flow, 'msg') == null
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
    def result = MultipartHelper.tryBlock(ctx, flow, 'blocked!')

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
    MultipartHelper.tryBlock(ctx, flow, 'msg')

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
    MultipartHelper.tryBlock(ctx, flow, 'msg') == null
  }
}
