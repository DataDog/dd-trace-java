package com.datadog.iast.test

import datadog.trace.api.iast.SourceTypes
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.hamcrest.Matchers
import spock.lang.Requires

abstract class IastSourcesTest<SERVER> extends IastHttpServerTest<SERVER> {

  @Requires({ !instance.ignoreHeaders() })
  void 'test header source'() {
    when:
    final url = "${address}/iast/sources/header"
    final request = new Request.Builder().url(url).header('name', 'value').get().build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'value'
      range 0, 5, source(SourceTypes.REQUEST_HEADER_VALUE, 'name', 'value')
    }
  }

  @Requires({ !instance.ignoreCookies() })
  void 'test cookie source'() {
    when:
    final url = "${address}/iast/sources/cookie"
    final request = new Request.Builder().url(url).header('Cookie', 'name=value').get().build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'value'
      range 0, 5, source(SourceTypes.REQUEST_COOKIE_VALUE, 'name', 'value')
    }
  }

  @Requires({ !instance.ignorePath() })
  void 'test path source'() {
    when:
    final url = "${address}/iast/sources/path/value"
    final request = new Request.Builder().url(url).get().build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'value'
      range 0, 5, source(SourceTypes.REQUEST_PATH_PARAMETER, 'name', 'value')
    }
  }

  @Requires({ !instance.ignoreParameters() })
  void 'test parameter source'() {
    when:
    final url = "${address}/iast/sources/parameter?name=value"
    final request = new Request.Builder().url(url).get().build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'value'
      range 0, 5, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value')
    }
  }

  @Requires({ !instance.ignoreForm() })
  void 'test form source'() {
    when:
    final url = "${address}/iast/sources/form"
    final body = new FormBody.Builder().add('name', 'value').build()
    final request = new Request.Builder().url(url).post(body).build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'value'
      range 0, 5, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value')
    }
  }

  @Requires({ !instance.ignoreBody() })
  void 'test body string source'() {
    when:
    final url = "${address}/iast/sources/body/string"
    final body = RequestBody.create(MediaType.get('text/plain'), 'string_body')
    final request = new Request.Builder().url(url).post(body).build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value Matchers.notNullValue()
      range source(SourceTypes.REQUEST_BODY)
    }
  }

  @Requires({ !instance.ignoreBody() })
  void 'test body json source'() {
    when:
    final url = "${address}/iast/sources/body/json"
    final body = RequestBody.create(MediaType.get('application/json'), '{ "name": "value" }')
    final request = new Request.Builder().url(url).post(body).build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value Matchers.notNullValue()
      range source(SourceTypes.REQUEST_BODY)
    }
  }

  protected boolean ignoreParameters() {
    return false
  }

  protected boolean ignoreHeaders() {
    return false
  }

  protected boolean ignorePath() {
    return false
  }

  protected boolean ignoreForm() {
    return false
  }

  protected boolean ignoreBody() {
    return false
  }

  protected boolean ignoreCookies() {
    return false
  }
}
