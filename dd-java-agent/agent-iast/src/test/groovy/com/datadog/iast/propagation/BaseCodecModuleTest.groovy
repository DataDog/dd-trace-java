package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import groovy.transform.CompileDynamic

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat

@CompileDynamic
abstract class BaseCodecModuleTest extends IastModuleImplTestBase {

  protected CodecModule module

  def setup() {
    module = buildModule()
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
      final sourceTainted = taintedObjects.get(parsed)
      assertOnUrlDecode(value, encoding, sourceTainted, to)
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
      final sourceTainted = taintedObjects.get(parsed)
      assertOnStringGetBytes(value, charset, sourceTainted, to)
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
      final sourceTainted = taintedObjects.get(parsed)
      assertOnStringFromBytes(bytes, 0, bytes.length, charset, sourceTainted, to)
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
      final sourceTainted = taintedObjects.get(parsed)
      assertBase64Decode(parsedBytes, sourceTainted, to)
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
      final sourceTainted = taintedObjects.get(parsed)
      assertBase64Encode(parsedBytes, sourceTainted, to)
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

  protected abstract void assertOnUrlDecode(final String value, final String encoding, final TaintedObject source, final TaintedObject target)

  protected abstract void assertOnStringFromBytes(final byte[] value, final int offset, final int length, final String encoding, final TaintedObject source, final TaintedObject target)

  protected abstract void assertOnStringGetBytes(final String value, final String encoding, final TaintedObject source, final TaintedObject target)

  protected abstract void assertBase64Decode(final byte[] value, final TaintedObject source, final TaintedObject target)

  protected abstract void assertBase64Encode(final byte[] value, final TaintedObject source, final TaintedObject target)

  protected abstract CodecModule buildModule()
}
