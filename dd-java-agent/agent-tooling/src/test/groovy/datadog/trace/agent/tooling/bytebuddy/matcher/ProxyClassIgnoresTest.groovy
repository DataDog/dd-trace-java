package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.test.util.DDSpecification

class ProxyClassIgnoresTest extends DDSpecification {
  def 'test #clsName exclusion'() {
    given:
    def result = ProxyClassIgnores.isIgnored(clsName)
    expect:
    result == excluded
    where:
    clsName                                                                                                                                                   | excluded
    'org.springframework.util.ConcurrentReferenceHashMap$4'                                                                                                   | false
    'io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration$CircuitBreakerEndpointAutoConfiguration$$SpringCGLIB$$0' | true
    'io.micrometer.core.instrument.config.NamingConvention$$Lambda$1415'                                                                                      | false
    'com.package.name.Class$JaxbAccessorF_variablename'                                                                                                       | true
    'my.package.Resource$Proxy$_$$_Weld$EnterpriseProxy'                                                                                                      | true
    'com.abc.MyResourceImpl$$EnhancerByGuice$$69175a50'                                                                                                       | true
  }
}
