package com.datadog.appsec.config

import spock.lang.Specification

class AppSecSCAConfigDeserializerTest extends Specification {

  def "deserializes valid JSON array from backend"() {
    given:
    def json = '''
      [
        {
          "advisory": "GHSA-24rp-q3w6-vc56",
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
      ]
    '''
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.vulnerabilities.size() == 1
    config.vulnerabilities[0].advisory == "GHSA-24rp-q3w6-vc56"
    config.vulnerabilities[0].cve == "CVE-2024-1597"
    config.vulnerabilities[0].vulnerableInternalCode.className == "org.postgresql.core.v3.SimpleParameterList"
    config.vulnerabilities[0].vulnerableInternalCode.methodName == "toString"
    config.vulnerabilities[0].externalEntrypoint.className == "org.postgresql.jdbc.PgPreparedStatement"
    config.vulnerabilities[0].externalEntrypoint.methods == ["executeQuery", "executeUpdate", "execute"]
  }

  def "returns null for null content"() {
    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(null)

    then:
    config == null
  }

  def "returns null for empty byte array"() {
    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(new byte[0])

    then:
    config == null
  }

  def "deserializes empty array"() {
    given:
    def json = '[]'
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.vulnerabilities != null
    config.vulnerabilities.isEmpty()
  }

  def "handles multiple vulnerabilities"() {
    given:
    def json = '''
      [
        {
          "advisory": "GHSA-1111-2222-3333",
          "cve": "CVE-2024-0001",
          "vulnerable_internal_code": {
            "class": "com.example.Class1",
            "method": "method1"
          }
        },
        {
          "advisory": "GHSA-4444-5555-6666",
          "cve": "CVE-2024-0002",
          "vulnerable_internal_code": {
            "class": "com.example.Class2",
            "method": "method2"
          }
        },
        {
          "advisory": "GHSA-7777-8888-9999",
          "cve": "CVE-2024-0003",
          "vulnerable_internal_code": {
            "class": "com.example.Class3",
            "method": "method3"
          }
        }
      ]
    '''
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.vulnerabilities.size() == 3

    config.vulnerabilities[0].advisory == "GHSA-1111-2222-3333"
    config.vulnerabilities[0].cve == "CVE-2024-0001"
    config.vulnerabilities[0].vulnerableInternalCode.className == "com.example.Class1"
    config.vulnerabilities[0].vulnerableInternalCode.methodName == "method1"

    config.vulnerabilities[1].advisory == "GHSA-4444-5555-6666"
    config.vulnerabilities[1].cve == "CVE-2024-0002"
    config.vulnerabilities[1].vulnerableInternalCode.className == "com.example.Class2"
    config.vulnerabilities[1].vulnerableInternalCode.methodName == "method2"

    config.vulnerabilities[2].advisory == "GHSA-7777-8888-9999"
    config.vulnerabilities[2].cve == "CVE-2024-0003"
    config.vulnerabilities[2].vulnerableInternalCode.className == "com.example.Class3"
    config.vulnerabilities[2].vulnerableInternalCode.methodName == "method3"
  }

  def "INSTANCE is a singleton"() {
    expect:
    AppSecSCAConfigDeserializer.INSTANCE === AppSecSCAConfigDeserializer.INSTANCE
  }

  def "deserializes complete vulnerability with all fields"() {
    given:
    def json = '''
      [
        {
          "advisory": "GHSA-77xx-rxvh-q682",
          "cve": "CVE-2022-41853",
          "vulnerable_internal_code": {
            "class": "org.hsqldb.Routine",
            "method": "getMethods"
          },
          "external_entrypoint": {
            "class": "org.hsqldb.jdbc.JDBCStatement",
            "methods": ["execute", "executeQuery", "executeUpdate"]
          },
          "description": "HSQLDB RCE vulnerability"
        }
      ]
    '''
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.vulnerabilities != null
    config.vulnerabilities.size() == 1
    config.vulnerabilities[0].advisory == "GHSA-77xx-rxvh-q682"
    config.vulnerabilities[0].cve == "CVE-2022-41853"
    config.vulnerabilities[0].vulnerableInternalCode.className == "org.hsqldb.Routine"
    config.vulnerabilities[0].vulnerableInternalCode.methodName == "getMethods"
    config.vulnerabilities[0].externalEntrypoint.className == "org.hsqldb.jdbc.JDBCStatement"
    config.vulnerabilities[0].externalEntrypoint.methods == ["execute", "executeQuery", "executeUpdate"]
  }
}
