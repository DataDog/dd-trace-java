import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.hibernate.Query
import org.hibernate.Session

class QueryTest extends AbstractHibernateTest {

  def "test hibernate query.#queryMethodName single call"() {
    setup:

    // With Transaction
    Session session = sessionFactory.openSession()
    session.beginTransaction()
    queryInteraction(session)
    session.getTransaction().commit()
    session.close()

    // Without Transaction
    if (!requiresTransaction) {
      session = sessionFactory.openSession()
      queryInteraction(session)
      session.close()
    }

    expect:
    assertTraces(requiresTransaction ? 1 : 2) {
      // With Transaction
      trace(4) {
        span {
          serviceName "hibernate"
          resourceName "hibernate.session"
          operationName "hibernate.session"
          spanType DDSpanTypes.HIBERNATE
          parent()
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-hibernate"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hibernate"
          resourceName "hibernate.transaction.commit"
          operationName "hibernate.transaction.commit"
          spanType DDSpanTypes.HIBERNATE
          childOf span(0)
          topLevel false
          tags {
            "$Tags.COMPONENT" "java-hibernate"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hibernate"
          resourceName "$resource"
          operationName "hibernate.$queryMethodName"
          spanType DDSpanTypes.HIBERNATE
          childOf span(0)
          topLevel false
          tags {
            "$Tags.COMPONENT" "java-hibernate"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "h2"
          spanType "sql"
          childOf span(2)
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "db1"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" CharSequence
            defaultTags()
          }
        }
      }
      if (!requiresTransaction) {
        // Without Transaction
        trace(3) {
          span {
            serviceName "hibernate"
            resourceName "hibernate.session"
            operationName "hibernate.session"
            spanType DDSpanTypes.HIBERNATE
            parent()
            tags {
              "$Tags.COMPONENT" "java-hibernate"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              defaultTags()
            }
          }
          span {
            serviceName "hibernate"
            resourceName "$resource"
            operationName "hibernate.$queryMethodName"
            spanType DDSpanTypes.HIBERNATE
            childOf span(0)
            tags {
              "$Tags.COMPONENT" "java-hibernate"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              defaultTags()
            }
          }
          span {
            serviceName "h2"
            spanType "sql"
            childOf span(1)
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" "h2"
              "$Tags.DB_INSTANCE" "db1"
              "$Tags.DB_USER" "sa"
              "$Tags.DB_OPERATION" CharSequence
              defaultTags()
            }
          }
        }
      }
    }

    where:
    queryMethodName       | resource                         | requiresTransaction | queryInteraction
    "query.list"          | "Value"                          | false               | { sess ->
      Query q = sess.createQuery("from Value")
      q.list()
    }
    "query.executeUpdate" | "update Value set name = 'alyx'" | true                | { sess ->
      Query q = sess.createQuery("update Value set name = 'alyx'")
      q.executeUpdate()
    }
    "query.uniqueResult"  | "Value"                          | false               | { sess ->
      Query q = sess.createQuery("from Value where id = 1")
      q.uniqueResult()
    }
    "iterate"             | "from Value"                     | false               | { sess ->
      Query q = sess.createQuery("from Value")
      q.iterate()
    }
    "query.scroll"        | "from Value"                     | false               | { sess ->
      Query q = sess.createQuery("from Value")
      q.scroll()
    }
  }

  def "test hibernate query.iterate"() {
    setup:

    Session session = sessionFactory.openSession()
    session.beginTransaction()
    Query q = session.createQuery("from Value")
    Iterator it = q.iterate()
    while (it.hasNext()) {
      it.next()
    }
    session.getTransaction().commit()
    session.close()

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          serviceName "hibernate"
          resourceName "hibernate.session"
          operationName "hibernate.session"
          spanType DDSpanTypes.HIBERNATE
          parent()
          tags {
            "$Tags.COMPONENT" "java-hibernate"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hibernate"
          resourceName "hibernate.transaction.commit"
          operationName "hibernate.transaction.commit"
          spanType DDSpanTypes.HIBERNATE
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-hibernate"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "hibernate"
          resourceName "from Value"
          operationName "hibernate.iterate"
          spanType DDSpanTypes.HIBERNATE
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-hibernate"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "h2"
          spanType "sql"
          childOf span(2)
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "db1"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_OPERATION" "select"
            defaultTags()
          }
        }
      }
    }
  }
}
