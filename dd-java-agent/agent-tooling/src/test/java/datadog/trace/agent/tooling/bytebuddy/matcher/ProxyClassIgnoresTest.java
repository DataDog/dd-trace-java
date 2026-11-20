package datadog.trace.agent.tooling.bytebuddy.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.util.DDJavaSpecification;
import org.tabletest.junit.TableTest;

class ProxyClassIgnoresTest extends DDJavaSpecification {

  @TableTest({
    "scenario               | className                                                                                                                                                 | excluded",
    "spring anonymous class | 'org.springframework.util.ConcurrentReferenceHashMap$4'                                                                                                   | false   ",
    "spring CGLIB proxy     | 'io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration$CircuitBreakerEndpointAutoConfiguration$$SpringCGLIB$$0' | true    ",
    "lambda                 | 'io.micrometer.core.instrument.config.NamingConvention$$Lambda$1415'                                                                                      | false   ",
    "JAXB accessor          | 'com.package.name.Class$JaxbAccessorF_variablename'                                                                                                       | true    ",
    "Weld proxy             | 'my.package.Resource$Proxy$_$$_Weld$EnterpriseProxy'                                                                                                      | true    ",
    "Guice enhancer         | 'com.abc.MyResourceImpl$$EnhancerByGuice$$69175a50'                                                                                                       | true    "
  })
  void testExclusion(String className, boolean excluded) {
    // given
    boolean result = ProxyClassIgnores.isIgnored(className);
    // expect
    assertEquals(excluded, result);
  }
}
