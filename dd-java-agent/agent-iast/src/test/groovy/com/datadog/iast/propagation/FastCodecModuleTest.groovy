package com.datadog.iast.propagation

import com.datadog.iast.model.Source
import com.datadog.iast.taint.Ranges
import com.datadog.iast.taint.TaintedObject
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.CodecModule
import com.datadog.iast.model.Range

import java.nio.charset.StandardCharsets

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
  protected void assertOnStringFromBytes(final byte[] value, final int offset, final int length, final String charset, final TaintedObject source, final TaintedObject target) {
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
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == Integer.MAX_VALUE // unbound for non char sequences
    assert range.source == sourceRange.source
  }

  @Override
  protected void assertBase64Decode(byte[] value, TaintedObject source, TaintedObject target) {
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == Integer.MAX_VALUE // unbound for non char sequences
    assert range.source == sourceRange.source
  }

  @Override
  protected void assertBase64Encode(byte[] value, TaintedObject source, TaintedObject target) {
    assert target.ranges.size() == 1

    final sourceRange = Ranges.highestPriorityRange(source.ranges)
    final range = target.ranges.first()
    assert range.start == 0
    assert range.length == Integer.MAX_VALUE // unbound for non char sequences
    assert range.source == sourceRange.source
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
    helloTainted.ranges.first().with {
      assert it.source.origin == (byte) 0
      assert it.source.name == 'name1'
      assert it.source.value == 'Hello'
    }

    when:
    final world = string.substring(6, 12)
    module.onStringFromBytes(bytes, 6, 6, charset.name(), world)

    then:
    final worldTainted = to.get(world)
    worldTainted.ranges.length == 1
    worldTainted.ranges.first().with {
      assert it.source.origin == (byte) 1
      assert it.source.name == 'name2'
      assert it.source.value == 'World!'
    }
  }
}
