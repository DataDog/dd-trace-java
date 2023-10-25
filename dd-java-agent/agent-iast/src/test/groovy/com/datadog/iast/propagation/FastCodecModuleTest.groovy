package com.datadog.iast.propagation

import com.datadog.iast.taint.Ranges
import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.iast.propagation.CodecModule
import groovy.transform.CompileDynamic


@CompileDynamic
class FastCodecModuleTest extends BaseCodecModuleTest {

  @Override
  protected CodecModule buildModule() {
    return new FastCodecModule()
  }

  @Override
  protected void assertOnUrlDecode(final String value, final String encoding, final TaintedObject source, final TaintedObject target) {
    final result = target.get() as String
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == result.length()
    assert range.source == sourceRange.source
  }

  @Override
  protected void assertOnStringFromBytes(final byte[] value, final String charset, final TaintedObject source, final TaintedObject target) {
    final result = target.get() as String
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == result.length()
    assert range.source == sourceRange.source
  }

  @Override
  protected void assertOnStringGetBytes(final String value, final String charset, final TaintedObject source, final TaintedObject target) {
    final result = target.get() as byte[]
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == result.length
    assert range.source == sourceRange.source
  }

  @Override
  protected void assertBase64Decode(byte[] value, TaintedObject source, TaintedObject target) {
    final result = target.get() as byte[]
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == result.length
    assert range.source == sourceRange.source
  }

  @Override
  protected void assertBase64Encode(byte[] value, TaintedObject source, TaintedObject target) {
    final result = target.get() as byte[]
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == result.length
    assert range.source == sourceRange.source
  }
}
