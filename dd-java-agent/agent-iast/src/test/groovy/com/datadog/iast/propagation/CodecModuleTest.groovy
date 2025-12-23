package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.Ranges
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

import java.nio.charset.StandardCharsets

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class CodecModuleTest extends IastModuleImplTestBase {

  protected CodecModule module

  def setup() {
    module = new CodecModuleImpl()
    InstrumentationBridge.registerIastModule(module)
  }

  @Override
  protected AgentTracer.TracerAPI buildAgentTracer() {
    return Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
      getTraceSegment() >> traceSegment
    }
  }

  void '#method null'() {
    when:
    module.&"$method".call(args.toArray())

    then:
    0 * _

    where:
    method              | args
    'onUrlDecode'       | ['test', 'utf-8', null]
    'onStringGetBytes'  | ['test', 'utf-8', null]
    'onStringFromBytes' | ['test'.bytes, 0, 2, 'utf-8', null]
    'onBase64Encode'    | ['test'.bytes, null]
    'onBase64Decode'    | ['test'.bytes, null]
  }

  void '#method no context'() {
    when:
    module.&"$method".call(args.toArray())

    then:
    1 * tracer.activeSpan() >> null

    where:
    method              | args
    'onUrlDecode'       | ['test', 'utf-8', 'decoded']
    'onStringGetBytes'  | ['test', 'utf-8', 'test'.getBytes('utf-8')]
    'onStringFromBytes' | ['test'.getBytes('utf-8'), 0, 2, 'utf-8', 'test']
    'onBase64Encode'    | ['test'.bytes, 'dGVzdA=='.bytes]
    'onBase64Decode'    | ['dGVzdA=='.bytes, 'test'.bytes]
  }

  void 'onUrlDecode (#value, #encoding)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final parsed = addFromTaintFormat(taintedObjects, value)
    final boolean isTainted = parsed != value
    final result = encoding == null ? URLDecoder.decode(parsed) : URLDecoder.decode(parsed, encoding)

    when:
    module.onUrlDecode(parsed, encoding, result)

    then:
    final to = taintedObjects.get(result)
    if (isTainted) {
      assert to != null
      assert to.get() == result
      assert to.ranges.size() == 1

      final sourceTainted = taintedObjects.get(parsed)
      final sourceRange = Ranges.highestPriorityRange(sourceTainted.ranges)
      final range = to.ranges.first()
      assert range.start == 0
      assert range.length == result.length()
      assert range.source == sourceRange.source
    } else {
      assert to == null
    }

    where:
    value                                                                                                  | encoding
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches'       | null
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches'       | 'utf-8'
    '==>https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches<==' | null
    '==>https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches<==' | 'utf-8'
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2F==>login<==%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches' | null
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2F==>login<==%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches' | 'utf-8'
  }

  void 'onStringGetBytes (#value, #charset)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final parsed = addFromTaintFormat(taintedObjects, value)
    final boolean isTainted = parsed != value
    final result = charset == null ? parsed.getBytes() : parsed.getBytes(charset)

    when:
    module.onStringGetBytes(parsed, charset, result)

    then:
    final to = taintedObjects.get(result)
    if (isTainted) {
      assert to != null
      assert to.get() == result
      assert to.ranges.size() == 1

      final sourceTainted = taintedObjects.get(parsed)
      final sourceRange = Ranges.highestPriorityRange(sourceTainted.ranges)
      final range = to.ranges.first()
      assert range.start == 0
      assert range.length == Integer.MAX_VALUE // unbound for non char sequences
      assert range.source == sourceRange.source
    } else {
      assert to == null
    }

    where:
    value                | charset
    'Hello World!'       | null
    'Hello World!'       | 'utf-8'
    '==>Hello World!<==' | null
    '==>Hello World!<==' | 'utf-8'
    'Hello ==>World!<==' | null
    'Hello ==>World!<==' | 'utf-8'
    '==>Hello<== World!' | null
    '==>Hello<== World!' | 'utf-8'
  }

  void 'onStringFromBytes (#value, #charset)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final parsed = addFromTaintFormat(taintedObjects, value)
    final boolean isTainted = parsed != value
    final bytes = charset == null ? parsed.getBytes() : parsed.getBytes(charset)
    if (isTainted) {
      ctx.taintedObjects.taint(bytes, taintedObjects.get(parsed).ranges)
    }
    final result = charset == null ? new String(bytes) : new String(bytes, (String) charset)

    when:
    module.onStringFromBytes(bytes, 0, bytes.length, charset, result)

    then:
    final to = taintedObjects.get(result)
    if (isTainted) {
      assert to != null
      assert to.get() == result
      assert to.ranges.size() == 1

      final sourceTainted = taintedObjects.get(parsed)
      final sourceRange = Ranges.highestPriorityRange(sourceTainted.ranges)
      final range = to.ranges.first()
      assert range.start == 0
      assert range.length == result.length()
      assert range.source == sourceRange.source
    } else {
      assert to == null
    }

    where:
    value                | charset
    'Hello World!'       | null
    'Hello World!'       | 'utf-8'
    '==>Hello World!<==' | null
    '==>Hello World!<==' | 'utf-8'
    'Hello ==>World!<==' | null
    'Hello ==>World!<==' | 'utf-8'
    '==>Hello<== World!' | null
    '==>Hello<== World!' | 'utf-8'
  }

  void 'onBase64Decode (#value)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final parsed = addFromTaintFormat(taintedObjects, value)
    final boolean isTainted = parsed != value
    final parsedBytes = Base64.getEncoder().encode(parsed.bytes)
    if (isTainted) {
      ctx.taintedObjects.taint(parsedBytes, taintedObjects.get(parsed).ranges)
    }
    final result = Base64.getDecoder().decode(parsedBytes)

    when:
    module.onBase64Decode(parsedBytes, result)

    then:
    final to = taintedObjects.get(result)
    if (isTainted) {
      assert to != null
      assert to.get() == result
      assert to.ranges.length == 1

      final sourceTainted = taintedObjects.get(parsed)
      final sourceRange = Ranges.highestPriorityRange(sourceTainted.ranges)
      final range = to.ranges.first()
      assert range.start == 0
      assert range.length == Integer.MAX_VALUE // unbound for non char sequences
      assert range.source == sourceRange.source
    } else {
      assert to == null
    }

    where:
    value                | _
    'Hello World!'       | _
    'Hello World!'       | _
    '==>Hello World!<==' | _
    '==>Hello World!<==' | _
    'Hello ==>World!<==' | _
    'Hello ==>World!<==' | _
    '==>Hello<== World!' | _
    '==>Hello<== World!' | _
  }

  void 'onBase64Encode (#value)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final parsed = addFromTaintFormat(taintedObjects, value)
    final boolean isTainted = parsed != value
    final parsedBytes = parsed.bytes
    if (isTainted) {
      ctx.taintedObjects.taint(parsedBytes, taintedObjects.get(parsed).ranges)
    }
    final result = Base64.getEncoder().encode(parsedBytes)

    when:
    module.onBase64Encode(parsedBytes, result)

    then:
    final to = taintedObjects.get(result)
    if (isTainted) {
      assert to != null
      assert to.get() == result
      assert to.ranges.length == 1

      final sourceTainted = taintedObjects.get(parsed)
      final sourceRange = Ranges.highestPriorityRange(sourceTainted.ranges)
      final range = to.ranges.first()
      assert range.start == 0
      assert range.length == Integer.MAX_VALUE // unbound for non char sequences
      assert range.source == sourceRange.source
    } else {
      assert to == null
    }

    where:
    value                | _
    'Hello World!'       | _
    'Hello World!'       | _
    '==>Hello World!<==' | _
    '==>Hello World!<==' | _
    'Hello ==>World!<==' | _
    'Hello ==>World!<==' | _
    '==>Hello<== World!' | _
    '==>Hello<== World!' | _
  }

  void 'test on string from bytes with multiple ranges'() {
    given:
    final charset = StandardCharsets.UTF_8
    final string = "Hello World!"
    final bytes = string.getBytes(charset) // 1 byte pe char
    final TaintedObjects to = ctx.taintedObjects
    final ranges = [
      new Range(0, 5, new Source((byte) 0, 'name1', 'Hello'), VulnerabilityMarks.NOT_MARKED),
      new Range(6, 6, new Source((byte) 1, 'name2', 'World!'), VulnerabilityMarks.NOT_MARKED)
    ]
    to.taint(bytes, ranges as Range[])

    when:
    final hello = string.substring(0, 5)
    module.onStringFromBytes(bytes, 0, 5, charset.name(), hello)

    then:
    final helloTainted = to.get(hello)
    helloTainted.ranges.length == 1
    with(helloTainted.ranges.first()) {
      it.source.origin == (byte) 0
      it.source.name == 'name1'
      it.source.value == 'Hello'
    }

    when:
    final world = string.substring(6, 12)
    module.onStringFromBytes(bytes, 6, 6, charset.name(), world)

    then:
    final worldTainted = to.get(world)
    worldTainted.ranges.length == 1
    with(worldTainted.ranges.first()) {
      it.source.origin == (byte) 1
      it.source.name == 'name2'
      it.source.value == 'World!'
    }
  }

  void 'test uri creation'() {
    given:
    final to = ctx.getTaintedObjects()
    final params = args.collect {
      return it instanceof String ? addFromTaintFormat(to, it as String) : it
    }.toArray()
    final uri = URI.metaClass.&invokeConstructor(params) as URI

    when:
    module.onUriCreate(uri, params)

    then:
    final rangeCount = fromTaintFormat(expected)?.length
    assert uri.toString() == getStringFromTaintFormat(expected)
    if (rangeCount > 0) {
      final tainted = to.get(uri)
      assert taintFormat(uri.toString(), tainted.ranges) == expected
    } else {
      assert to.get(uri) == null
    }

    where:
    args                                                                                | expected
    ['http://test.com/index?name=value#fragment']                                       | 'http://test.com/index?name=value#fragment'
    ['==>http<==://test.com/index?name=value#fragment']                                 | '==>http<==://test.com/index?name=value#fragment'
    ['http://test.com/index?==>name=value<==#fragment']                                 | 'http://test.com/index?==>name=value<==#fragment'
    ['http', 'user:password', 'test.com', 80, '/index', 'name=value', 'fragment']       | 'http://user:password@test.com:80/index?name=value#fragment'
    ['==>http<==', 'user:password', 'test.com', 80, '/index', 'name=value', 'fragment'] | '==>http<==://user:password@test.com:80/index?name=value#fragment'
    ['http', 'user:password', 'test.com', 80, '/index', '==>name=value<==', 'fragment'] | 'http://user:password@test.com:80/index?==>name=value<==#fragment'
  }

  void 'test url creation'() {
    given:
    final to = ctx.getTaintedObjects()
    final params = args.collect {
      def result = it
      if (it instanceof String) {
        result = addFromTaintFormat(to, it as String)
      } else if (it instanceof TaintedURL) {
        final format = (it as TaintedURL).url
        result = new URL(getStringFromTaintFormat(format))
        final ranges = fromTaintFormat(format)
        if (ranges?.length > 0) {
          to.taint(result, ranges)
        }
      }
      return result
    }.toArray()
    final url = URL.metaClass.&invokeConstructor(params) as URL

    when:
    module.onUrlCreate(url, params)

    then:
    final rangeCount = fromTaintFormat(expected)?.length
    assert url.toString() == getStringFromTaintFormat(expected)
    if (rangeCount > 0) {
      final tainted = to.get(url)
      assert taintFormat(url.toString(), tainted.ranges) == expected
    } else {
      assert to.get(url) == null
    }

    where:
    args                                                                                            | expected
    ['http://test.com/index?name=value#fragment']                                                   | 'http://test.com/index?name=value#fragment'
    ['==>http<==://test.com/index?name=value#fragment']                                             | '==>http<==://test.com/index?name=value#fragment'
    ['http://test.com/index?==>name=value<==#fragment']                                             | 'http://test.com/index?==>name=value<==#fragment'
    ['http', 'test.com', 80, '/index?name=value#fragment']                                          | 'http://test.com:80/index?name=value#fragment'
    ['==>http<==', 'test.com', 80, '/index?name=value#fragment']                                    | '==>http<==://test.com:80/index?name=value#fragment'
    ['http', 'test.com', 80, '/index?==>name=value<==#fragment']                                    | 'http://test.com:80/index?==>name=value<==#fragment'
    [new TaintedURL('http://test.com'), '/index?name=value#fragment']                               | 'http://test.com/index?name=value#fragment'
    [new TaintedURL('==>http<==://test.com'), '/index?name=value#fragment']                         | '==>http<==://test.com/index?name=value#fragment'
    [new TaintedURL('http://test.com'), '/index?==>name=value<==#fragment']                         | 'http://test.com/index?==>name=value<==#fragment'
    [new TaintedURL('==>http<==://test.com'), '/index?==>name=value<==#fragment']                   | '==>http<==://test.com/index?==>name=value<==#fragment'
    [null, 'http://test.com/index?==>name=value<==#fragment']                                       | 'http://test.com/index?==>name=value<==#fragment'
    [new TaintedURL('==>http<==://ignored.com'), 'http://test.com/index?name=value#fragment']       | 'http://test.com/index?name=value#fragment'
    [new TaintedURL('==>http<==://ignored.com'), '==>http<==://test.com/index?name=value#fragment'] | '==>http<==://test.com/index?name=value#fragment'
    [new TaintedURL('==>http<==://ignored.com'), 'http://test.com/index?==>name=value<==#fragment'] | 'http://test.com/index?==>name=value<==#fragment'
  }

  protected static class TaintedURL {
    private final String url

    protected TaintedURL(String url) {
      this.url = url
    }

    @Override
    String toString() {
      return "TaintedURL{" +
        "url='" + url + '\'' +
        '}'
    }
  }
}
