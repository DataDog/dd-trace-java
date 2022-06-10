package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

class ServiceNameHashProvidersTest extends DDSpecification {

  def "calc service name hash"() {
    setup:
    def sha256First10 = ServiceNameHashing.getHashProvider(true)
    def noOp = ServiceNameHashing.getHashProvider(false)

    expect:
    sha256First10.hash(svcName) == svcHash
    noOp.hash(svcName) == ""

    where:
    svcName                        | svcHash
    "service1"                     | "e9c34be8fd"
    "my-svc"                       | "5eb583bba6"
    "aService"                     | "faec2ae2b4"
    "The_Service"                  | "b4ac2d3063"
    "some-super-long-service-name" | "f177ecb2ef"
    // cover getting hashes from the cache
    "service1"                     | "e9c34be8fd"
    "my-svc"                       | "5eb583bba6"
    "aService"                     | "faec2ae2b4"
    "The_Service"                  | "b4ac2d3063"
    "some-super-long-service-name" | "f177ecb2ef"
  }
}
