package com.datadog.appsec.config

import com.squareup.moshi.Moshi
import spock.lang.Specification

class AppSecSCAConfigTest extends Specification {

  def "deserializes valid SCA config with vulnerabilities"() {
    given:
    def json = '''
      {
        "vulnerabilities": [
          {
            "advisory": "GHSA-xxxx-yyyy-zzzz",
            "cve": "CVE-2024-0001",
            "vulnerable_internal_code": {
              "class": "org.springframework.web.client.RestTemplate",
              "method": "execute"
            },
            "external_entrypoint": {
              "class": "org.springframework.web.RestTemplate",
              "methods": ["getForObject", "postForObject"]
            }
          },
          {
            "advisory": "GHSA-aaaa-bbbb-cccc",
            "cve": "CVE-2024-0002",
            "vulnerable_internal_code": {
              "class": "com.fasterxml.jackson.databind.ObjectMapper",
              "method": "readValue"
            },
            "external_entrypoint": {
              "class": "com.fasterxml.jackson.databind.ObjectMapper",
              "methods": ["readValue"]
            }
          }
        ]
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.vulnerabilities != null
    config.vulnerabilities.size() == 2

    config.vulnerabilities[0].advisory == "GHSA-xxxx-yyyy-zzzz"
    config.vulnerabilities[0].cve == "CVE-2024-0001"
    config.vulnerabilities[0].vulnerableInternalCode.className == "org.springframework.web.client.RestTemplate"
    config.vulnerabilities[0].vulnerableInternalCode.methodName == "execute"
    config.vulnerabilities[0].externalEntrypoint.className == "org.springframework.web.RestTemplate"
    config.vulnerabilities[0].externalEntrypoint.methods == ["getForObject", "postForObject"]

    config.vulnerabilities[1].advisory == "GHSA-aaaa-bbbb-cccc"
    config.vulnerabilities[1].cve == "CVE-2024-0002"
    config.vulnerabilities[1].vulnerableInternalCode.className == "com.fasterxml.jackson.databind.ObjectMapper"
    config.vulnerabilities[1].vulnerableInternalCode.methodName == "readValue"
  }

  def "deserializes SCA config with empty vulnerabilities"() {
    given:
    def json = '''
      {
        "vulnerabilities": []
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.vulnerabilities != null
    config.vulnerabilities.isEmpty()
  }

  def "deserializes minimal SCA config"() {
    given:
    def json = '''
      {
        "vulnerabilities": [
          {
            "advisory": "GHSA-1234-5678-90ab",
            "cve": "CVE-2024-9999",
            "vulnerable_internal_code": {
              "class": "com.example.Vulnerable",
              "method": "badMethod"
            }
          }
        ]
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.vulnerabilities != null
    config.vulnerabilities.size() == 1
    config.vulnerabilities[0].advisory == "GHSA-1234-5678-90ab"
    config.vulnerabilities[0].cve == "CVE-2024-9999"
  }

  def "handles empty JSON object"() {
    given:
    def json = '{}'

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.vulnerabilities == null
  }

  def "deserializes Vulnerability correctly"() {
    given:
    def json = '''
      {
        "advisory": "GHSA-test-1234-abcd",
        "cve": "CVE-2024-1597",
        "vulnerable_internal_code": {
          "class": "org.postgresql.core.v3.SimpleParameterList",
          "method": "toString"
        },
        "external_entrypoint": {
          "class": "org.postgresql.jdbc.PgPreparedStatement",
          "methods": ["executeQuery", "executeUpdate", "execute"]
        }
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig.Vulnerability)
    def vulnerability = adapter.fromJson(json)

    then:
    vulnerability != null
    vulnerability.advisory == "GHSA-test-1234-abcd"
    vulnerability.cve == "CVE-2024-1597"
    vulnerability.vulnerableInternalCode.className == "org.postgresql.core.v3.SimpleParameterList"
    vulnerability.vulnerableInternalCode.methodName == "toString"
    vulnerability.externalEntrypoint.className == "org.postgresql.jdbc.PgPreparedStatement"
    vulnerability.externalEntrypoint.methods.size() == 3
    vulnerability.externalEntrypoint.methods == ["executeQuery", "executeUpdate", "execute"]
  }
}