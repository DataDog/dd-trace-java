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

  // collectFilesContent

  private static MultivaluedMapImpl<String, String> headers(String cd) {
    def h = new MultivaluedMapImpl<String, String>()
    h.add('Content-Disposition', cd)
    h
  }

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
