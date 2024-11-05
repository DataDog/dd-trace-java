package datadog.trace.api.iast.util

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.test.util.DDSpecification

class PropagationUtilsTest extends DDSpecification {

  void 'test onUriCreate null'() {
    setup:
    final uri = URI.create(value)

    when:
    final result = PropagationUtils.onUriCreate(value, uri)

    then:
    result == uri
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onStringBuilderToString null'() {
    setup:
    StringBuilder sb = new StringBuilder(value)

    when:
    final result = PropagationUtils.onStringBuilderToString(sb, sb.toString())

    then:
    result == sb.toString()
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onStringBuilderAppend null'() {
    setup:
    StringBuilder sb = new StringBuilder()

    when:
    final result = PropagationUtils.onStringBuilderAppend(value, sb.append(value))

    then:
    result == sb
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onUriCreate'() {
    setup:
    final iastModule = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final uri = URI.create(value)

    when:
    final result = PropagationUtils.onUriCreate(value, uri)

    then:
    result == uri
    1 * iastModule.onUriCreate(_ as URI, _ as String)
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onStringBuilderToString'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    StringBuilder sb = new StringBuilder(value)

    when:
    final result = PropagationUtils.onStringBuilderToString(sb, sb.toString())

    then:
    result == sb.toString()
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, _ as String)
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onStringBuilderAppend'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    StringBuilder sb = new StringBuilder()

    when:
    final result = PropagationUtils.onStringBuilderAppend(value, sb.append(value))

    then:
    result == sb
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, _ as String)
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onUriCreate throw exception'() {
    setup:
    final iastModule = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final uri = URI.create(value)

    when:
    final result = PropagationUtils.onUriCreate(value, uri)

    then:
    result == uri
    1 * iastModule.onUriCreate(_ as URI, _ as String) >> { throw new Exception("test exception") }
    1 * iastModule.onUnexpectedException("onUriCreate threw", _ as Exception)
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onStringBuilderToString throw exception'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    StringBuilder sb = new StringBuilder(value)

    when:
    final result = PropagationUtils.onStringBuilderToString(sb, sb.toString())

    then:
    result == sb.toString()
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, _ as String) >> { throw new Exception("test exception") }
    1 * iastModule.onUnexpectedException("onStringBuilderToString threw", _ as Exception)
    0 * _

    where:
    value << ['http://test.com']
  }

  void 'test onStringBuilderAppend throw exception'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    StringBuilder sb = new StringBuilder()

    when:
    final result = PropagationUtils.onStringBuilderAppend(value, sb.append(value))

    then:
    result == sb
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, _ as String) >> { throw new Exception("test exception") }
    1 * iastModule.onUnexpectedException("onStringBuilderAppend threw", _ as Exception)
    0 * _

    where:
    value << ['http://test.com']
  }
}
