package com.datadog.iast.model.json

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.config.IastConfig
import datadog.trace.api.iast.SourceTypes
import datadog.trace.test.util.DDSpecification
import org.skyscreamer.jsonassert.JSONAssert

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

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
      [new Range(0, value.length(), new Source(sourceType, sourceName, sourceValue), NOT_MARKED)] as Range[])
  }
}
