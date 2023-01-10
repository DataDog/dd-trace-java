package com.datadog.iast.model.json

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import com.datadog.iast.taint.TaintedObject
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonSlurper

import java.lang.ref.ReferenceQueue

class TaintedObjectEncodingTest extends DDSpecification {

  void 'test tainted object'() {
    given:
    final slurper = new JsonSlurper()
    final value = taintedObject('test', SourceType.REQUEST_PARAMETER_NAME, 'key', 'value')

    when:
    final result = TaintedObjectEncoding.toJson(value)

    then:
    slurper.parseText(result) == slurper.parseText('''{
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
}''')
  }

  void 'test tainted object list'() {
    given:
    final slurper = new JsonSlurper()
    final value = [
      taintedObject('test1', SourceType.REQUEST_PARAMETER_NAME, 'key1', 'value1'),
      taintedObject('test2', SourceType.REQUEST_PARAMETER_VALUE, 'key2', 'value2')
    ]

    when:
    final result = TaintedObjectEncoding.toJson(value)

    then:
    slurper.parseText(result) == slurper.parseText('''[
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
]''')
  }

  private TaintedObject taintedObject(final String value, final byte sourceType, final String sourceName, final String sourceValue) {
    return new TaintedObject(
      value,
      [new Range(0, value.length(), new Source(sourceType, sourceName, sourceValue))] as Range[],
      Mock(ReferenceQueue))
  }
}
