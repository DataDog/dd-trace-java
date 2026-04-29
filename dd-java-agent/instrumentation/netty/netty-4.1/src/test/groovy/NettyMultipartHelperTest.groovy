import datadog.appsec.api.blocking.BlockingException
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.instrumentation.netty41.NettyMultipartHelper
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.multipart.Attribute
import io.netty.handler.codec.http.multipart.FileUpload
import io.netty.handler.codec.http.multipart.InterfaceHttpData
import spock.lang.Specification
import spock.lang.TempDir

import java.lang.reflect.UndeclaredThrowableException
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class NettyMultipartHelperTest extends Specification {

  @TempDir
  Path tempDir

  // =========================================================
  // readContent — in-memory path
  // =========================================================

  void 'readContent returns content from in-memory FileUpload'() {
    given:
    def upload = inMemoryUpload('hello world')

    expect:
    NettyMultipartHelper.readContent(upload) == 'hello world'
  }

  void 'readContent truncates in-memory content at MAX_CONTENT_BYTES'() {
    given:
    def upload = inMemoryUpload('X' * inputSize)

    when:
    def result = NettyMultipartHelper.readContent(upload)

    then:
    result.length() == expectedSize

    where:
    inputSize                                         | expectedSize
    13                                                | 13
    NettyMultipartHelper.MAX_CONTENT_BYTES - 1        | NettyMultipartHelper.MAX_CONTENT_BYTES - 1
    NettyMultipartHelper.MAX_CONTENT_BYTES            | NettyMultipartHelper.MAX_CONTENT_BYTES
    NettyMultipartHelper.MAX_CONTENT_BYTES + 500      | NettyMultipartHelper.MAX_CONTENT_BYTES
  }

  void 'readContent returns empty string for empty in-memory content'() {
    given:
    def upload = inMemoryUpload('')

    expect:
    NettyMultipartHelper.readContent(upload) == ''
  }

  void 'readContent does not advance readerIndex of the underlying ByteBuf'() {
    given:
    def content = 'sensitive data'
    def upload = inMemoryUpload(content)
    def buf = upload.getByteBuf()
    def indexBefore = buf.readerIndex()

    when:
    NettyMultipartHelper.readContent(upload)

    then:
    buf.readerIndex() == indexBefore
  }

  // =========================================================
  // readContent — disk-backed path
  // =========================================================

  void 'readContent returns content from disk-backed FileUpload'() {
    given:
    def upload = diskBackedUpload('hello from disk')

    expect:
    NettyMultipartHelper.readContent(upload) == 'hello from disk'
  }

  void 'readContent truncates disk-backed content at MAX_CONTENT_BYTES'() {
    given:
    def upload = diskBackedUpload('Y' * inputSize)

    when:
    def result = NettyMultipartHelper.readContent(upload)

    then:
    result.length() == expectedSize

    where:
    inputSize                                         | expectedSize
    13                                                | 13
    NettyMultipartHelper.MAX_CONTENT_BYTES - 1        | NettyMultipartHelper.MAX_CONTENT_BYTES - 1
    NettyMultipartHelper.MAX_CONTENT_BYTES            | NettyMultipartHelper.MAX_CONTENT_BYTES
    NettyMultipartHelper.MAX_CONTENT_BYTES + 500      | NettyMultipartHelper.MAX_CONTENT_BYTES
  }

  void 'readContent returns empty string for empty disk-backed file'() {
    given:
    def upload = diskBackedUpload('')

    expect:
    NettyMultipartHelper.readContent(upload) == ''
  }

  // =========================================================
  // readContent — charset decoding
  // =========================================================

  void 'readContent uses Content-Type charset for in-memory content'() {
    given:
    def text = 'héllo wörld'
    def buf = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> true
    upload.getByteBuf() >> buf
    upload.getContentType() >> 'text/plain; charset=UTF-8'

    expect:
    NettyMultipartHelper.readContent(upload) == text
  }

  void 'readContent uses Content-Type charset for disk-backed content'() {
    given:
    def text = 'héllo wörld'
    def file = tempDir.resolve('upload.bin').toFile()
    file.bytes = text.getBytes(StandardCharsets.UTF_8)
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> false
    upload.getFile() >> file
    upload.getContentType() >> 'text/plain; charset=UTF-8'

    expect:
    NettyMultipartHelper.readContent(upload) == text
  }

  // =========================================================
  // readContent — error handling
  // =========================================================

  void 'readContent returns empty string when getByteBuf throws'() {
    given:
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> true
    upload.getByteBuf() >> { throw new RuntimeException('simulated error') }

    expect:
    NettyMultipartHelper.readContent(upload) == ''
  }

  void 'readContent returns empty string when getFile throws'() {
    given:
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> false
    upload.getFile() >> { throw new IOException('simulated error') }

    expect:
    NettyMultipartHelper.readContent(upload) == ''
  }

  // =========================================================
  // collectBodyData — empty / null inputs
  // =========================================================

  void 'collectBodyData returns null and leaves collections empty when parts list is empty'() {
    given:
    def attributes = [:]
    def filenames = []
    def filesContent = []

    expect:
    NettyMultipartHelper.collectBodyData([], attributes, filenames, filesContent) == null
    attributes.isEmpty()
    filenames.isEmpty()
    filesContent.isEmpty()
  }

  // =========================================================
  // collectBodyData — attribute handling
  // =========================================================

  void 'collectBodyData collects attribute value into map'() {
    given:
    def attr = attribute('key', 'value')
    def attributes = [:]

    when:
    NettyMultipartHelper.collectBodyData([attr], attributes, null, null)

    then:
    attributes == [key: ['value']]
  }

  void 'collectBodyData accumulates multiple values for the same attribute key'() {
    given:
    def parts = [attribute('k', 'v1'), attribute('k', 'v2')]
    def attributes = [:]

    when:
    NettyMultipartHelper.collectBodyData(parts, attributes, null, null)

    then:
    attributes == [k: ['v1', 'v2']]
  }

  void 'collectBodyData skips attribute parts when attributes map is null'() {
    given:
    def attr = attribute('key', 'value')
    def filenames = []

    when:
    NettyMultipartHelper.collectBodyData([attr], null, filenames, null)

    then:
    filenames.isEmpty()
  }

  void 'collectBodyData wraps IOException from getValue as UndeclaredThrowableException'() {
    given:
    def cause = new IOException('disk error')
    Attribute attr = Stub(Attribute)
    attr.getHttpDataType() >> InterfaceHttpData.HttpDataType.Attribute
    attr.getName() >> 'k'
    attr.getValue() >> { throw cause }
    def attributes = [:]

    when:
    def exc = NettyMultipartHelper.collectBodyData([attr], attributes, null, null)

    then:
    exc instanceof UndeclaredThrowableException
    exc.cause.is(cause)
  }

  void 'collectBodyData continues collecting after an IOException from getValue'() {
    given:
    Attribute failing = Stub(Attribute)
    failing.getHttpDataType() >> InterfaceHttpData.HttpDataType.Attribute
    failing.getName() >> 'bad'
    failing.getValue() >> { throw new IOException('disk error') }

    def good = attribute('good', 'ok')
    def attributes = [:]

    when:
    NettyMultipartHelper.collectBodyData([failing, good], attributes, null, null)

    then:
    attributes == [bad: [], good: ['ok']]
  }

  // =========================================================
  // collectBodyData — filename handling
  // =========================================================

  void 'collectBodyData collects non-empty filename from file upload'() {
    given:
    def filenames = []

    when:
    NettyMultipartHelper.collectBodyData([fileUploadPart('report.pdf', '')], null, filenames, null)

    then:
    filenames == ['report.pdf']
  }

  void 'collectBodyData skips empty filename from file upload'() {
    given:
    def filenames = []

    when:
    NettyMultipartHelper.collectBodyData([fileUploadPart('', '')], null, filenames, null)

    then:
    filenames.isEmpty()
  }

  void 'collectBodyData skips null filename from file upload'() {
    given:
    FileUpload upload = Stub(FileUpload)
    upload.getHttpDataType() >> InterfaceHttpData.HttpDataType.FileUpload
    upload.getFilename() >> null
    upload.isInMemory() >> true
    upload.getByteBuf() >> Unpooled.EMPTY_BUFFER
    def filenames = []

    when:
    NettyMultipartHelper.collectBodyData([upload], null, filenames, null)

    then:
    filenames.isEmpty()
  }

  void 'collectBodyData skips filenames when filenames list is null'() {
    given:
    def filesContent = []

    when:
    NettyMultipartHelper.collectBodyData(
      [fileUploadPart('report.pdf', 'data')], null, null, filesContent)

    then:
    filesContent == ['data']
  }

  // =========================================================
  // collectBodyData — content handling
  // =========================================================

  void 'collectBodyData reads file content into filesContent list'() {
    given:
    def filesContent = []

    when:
    NettyMultipartHelper.collectBodyData(
      [fileUploadPart('f.txt', 'hello')], null, null, filesContent)

    then:
    filesContent == ['hello']
  }

  void 'collectBodyData skips content when filesContent list is null'() {
    given:
    def filenames = []

    when:
    NettyMultipartHelper.collectBodyData(
      [fileUploadPart('f.txt', 'hello')], null, filenames, null)

    then:
    filenames == ['f.txt']
  }

  void 'collectBodyData stops reading content after MAX_FILES_TO_INSPECT but keeps collecting filenames'() {
    given:
    def max = NettyMultipartHelper.MAX_FILES_TO_INSPECT
    def parts = (1..max + 1).collect { i -> fileUploadPart("file${i}.txt", "content${i}") }
    def filenames = []
    def filesContent = []

    when:
    NettyMultipartHelper.collectBodyData(parts, null, filenames, filesContent)

    then:
    filesContent.size() == max
    filenames.size() == max + 1
  }

  // =========================================================
  // collectBodyData — mixed parts
  // =========================================================

  void 'collectBodyData handles mixed attributes and file uploads in one pass'() {
    given:
    def parts = [
      attribute('field1', 'val1'),
      fileUploadPart('upload.txt', 'file-body'),
      attribute('field2', 'val2'),
    ]
    def attributes = [:]
    def filenames = []
    def filesContent = []

    when:
    def exc = NettyMultipartHelper.collectBodyData(parts, attributes, filenames, filesContent)

    then:
    exc == null
    attributes == [field1: ['val1'], field2: ['val2']]
    filenames == ['upload.txt']
    filesContent == ['file-body']
  }

  // =========================================================
  // tryBlock
  // =========================================================

  void 'tryBlock returns null when flow action is not a blocking action'() {
    given:
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> Flow.Action.Noop.INSTANCE
    RequestContext ctx = Stub(RequestContext)

    expect:
    NettyMultipartHelper.tryBlock(ctx, flow, 'msg') == null
  }

  void 'tryBlock returns BlockingException with provided message when brf commits response'() {
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
    def result = NettyMultipartHelper.tryBlock(ctx, flow, 'blocked!')

    then:
    result instanceof BlockingException
    result.message == 'blocked!'
  }

  void 'tryBlock calls tryCommitBlockingResponse on brf with segment and rba'() {
    given:
    def segment = Stub(TraceSegment)
    def rba = Stub(Flow.Action.RequestBlockingAction)
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> rba
    BlockResponseFunction brf = Mock(BlockResponseFunction)
    RequestContext ctx = Stub(RequestContext)
    ctx.getBlockResponseFunction() >> brf
    ctx.getTraceSegment() >> segment

    when:
    NettyMultipartHelper.tryBlock(ctx, flow, 'msg')

    then:
    1 * brf.tryCommitBlockingResponse(segment, rba)
  }

  void 'tryBlock returns null when brf is null despite blocking action'() {
    given:
    def rba = Stub(Flow.Action.RequestBlockingAction)
    Flow<Void> flow = Stub(Flow)
    flow.getAction() >> rba
    RequestContext ctx = Stub(RequestContext)
    ctx.getBlockResponseFunction() >> null

    expect:
    NettyMultipartHelper.tryBlock(ctx, flow, 'msg') == null
  }

  // =========================================================
  // helpers
  // =========================================================

  private FileUpload inMemoryUpload(String content) {
    def buf = Unpooled.copiedBuffer(content, StandardCharsets.ISO_8859_1)
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> true
    upload.getByteBuf() >> buf
    return upload
  }

  private FileUpload diskBackedUpload(String content) {
    def file = tempDir.resolve('upload.bin').toFile()
    file.bytes = content.getBytes(StandardCharsets.ISO_8859_1)
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> false
    upload.getFile() >> file
    return upload
  }

  private Attribute attribute(String name, String value) {
    Attribute attr = Stub(Attribute)
    attr.getHttpDataType() >> InterfaceHttpData.HttpDataType.Attribute
    attr.getName() >> name
    attr.getValue() >> value
    return attr
  }

  private FileUpload fileUploadPart(String filename, String content) {
    def buf = Unpooled.copiedBuffer(content, StandardCharsets.ISO_8859_1)
    FileUpload upload = Stub(FileUpload)
    upload.getHttpDataType() >> InterfaceHttpData.HttpDataType.FileUpload
    upload.getFilename() >> filename
    upload.isInMemory() >> true
    upload.getByteBuf() >> buf
    return upload
  }
}
