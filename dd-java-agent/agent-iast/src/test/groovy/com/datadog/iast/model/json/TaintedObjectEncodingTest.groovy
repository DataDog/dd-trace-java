package com.datadog.iast.model.json

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.config.IastConfig
import datadog.trace.api.iast.SourceTypes
import datadog.trace.test.util.DDSpecification
import org.skyscreamer.jsonassert.JSONAssert

import java.lang.ref.ReferenceQueue

class TaintedObjectEncodingTest extends DDSpecification {

  @Override
  void setup() {
    injectSysConfig(IastConfig.IAST_REDACTION_ENABLED, 'false')
  }

  void 'test tainted object'() {
    given:
    final value = taintedObject('test', SourceTypes.REQUEST_PARAMETER_NAME, 'key', 'value')

    when:
    final result = TaintedObjectEncoding.toJson(value)

    then:
    JSONAssert.assertEquals('''{
  "value": "test",
  "ranges": [
    {
      "source": {
        "origin": "http.request.parameter.name",
        "name": "key",
        "value": "value"
      },
      "start": 0,
      "length": 4
    }
  ]
}''', result, true)
  }

  void 'test tainted truncated object'() {
    given:
    final value = taintedObject('test', SourceTypes.REQUEST_PARAMETER_NAME, 'key', 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.Sed ut perspiciatis unde omnis iste natus error sit voluptatem ac')

    when:
    final result = TaintedObjectEncoding.toJson(value)

    then:
    JSONAssert.assertEquals('''{"value":"test","ranges":[{"source":{"origin":"http.request.parameter.name","name":"key","value":"Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure do","truncated":"right"},"start":0,"length":4}]}
''', result, true)
  }

  void 'test tainted object list'() {
    given:
    final value = [
      taintedObject('test1', SourceTypes.REQUEST_PARAMETER_NAME, 'key1', 'value1'),
      taintedObject('test2', SourceTypes.REQUEST_PARAMETER_VALUE, 'key2', 'value2')
    ]

    when:
    final result = TaintedObjectEncoding.toJson(value)

    then:
    JSONAssert.assertEquals('''[
  {
    "value": "test1",
    "ranges": [
      {
        "source": {
          "origin": "http.request.parameter.name",
          "name": "key1",
          "value": "value1"
        },
        "start": 0,
        "length": 5
      }
    ]
  },
  {
    "value": "test2",
    "ranges": [
      {
        "source": {
          "origin": "http.request.parameter",
          "name": "key2",
          "value": "value2"
        },
        "start": 0,
        "length": 5
      }
    ]
  }
]''', result, true)
  }

  private TaintedObject taintedObject(final String value, final byte sourceType, final String sourceName, final String sourceValue) {
    return new TaintedObject(
      value,
      [new Range(0, value.length(), new Source(sourceType, sourceName, sourceValue), Range.NOT_MARKED)] as Range[],
      Mock(ReferenceQueue))
  }
}
