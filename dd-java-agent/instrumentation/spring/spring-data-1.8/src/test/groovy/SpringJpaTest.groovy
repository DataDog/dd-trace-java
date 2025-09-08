// This file includes software developed at SignalFx

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spring.jpa.JpaCustomer
import spring.jpa.JpaCustomerRepository
import spring.jpa.JpaPersistenceConfig

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@Flaky("https://github.com/DataDog/dd-trace-java/issues/4004")
class SpringJpaTest extends InstrumentationSpecification {
  def "test object method"() {
    setup:
    def context = new AnnotationConfigApplicationContext(JpaPersistenceConfig)
    def repo = context.getBean(JpaCustomerRepository)

    // when Spring JPA sets up, it issues metadata queries -- clear those traces
    TEST_WRITER.clear()

    when:
    runUnderTrace("toString test") {
      repo.toString()
    }

    then:
    // Asserting that a span is NOT created for toString
    assertTraces(1) {
      trace(1) {
        span {
          operationName "toString test"
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "test CRUD"() {
    JpaCustomerRepository repo
    // moved inside test -- otherwise, miss the opportunity to instrument
    def setupSpan = runUnderTrace("setup") {
      def context = new AnnotationConfigApplicationContext(JpaPersistenceConfig)
      repo = context.getBean(JpaCustomerRepository)
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    // when Spring JPA sets up, it issues metadata queries -- clear those traces
    TEST_WRITER.clear()

    setup:
    injectSysConfig(TraceInstrumentationConfig.SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME, useEnhancedNaming)
    def customer = new JpaCustomer("Bob", "Anonymous")

    expect:
    customer.id == null
    !repo.findAll().iterator().hasNext() // select

    assertTraces(1) {
      trace(2) {
        span {
          operationName "repository.operation"
          resourceName "${intfName}.findAll"
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          // select
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "select"
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.save(customer) // insert
    def savedId = customer.id

    then:
    customer.id != null
    assertTraces(1) {
      trace(2) {
        span {
          operationName "repository.operation"
          resourceName "${intfName}.save"
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          // insert
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "insert"
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    customer.firstName = "Bill"
    repo.save(customer)

    then:
    customer.id == savedId
    assertTraces(1) {
      trace(3) {
        span {
          operationName "repository.operation"
          resourceName "${intfName}.save"
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "update"
            defaultTags()
          }
        }
        span {
          //update
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "select"
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    customer = repo.findByLastName("Anonymous")[0]

    then:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "repository.operation"
          resourceName "JpaCustomerRepository.findByLastName"
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "select"
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.delete(customer) //delete

    then:
    assertTraces(1) {
      trace(3) {
        span {
          operationName "repository.operation"
          resourceName "${intfName}.delete"
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "delete"
            defaultTags()
          }
        }
        span {
          serviceName "hsqldb"
          spanType "sql"
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "select"
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()
    where:
    useEnhancedNaming | intfName
    "true"            | "JpaCustomerRepository"
    "false"           | "CrudRepository"
  }
}
