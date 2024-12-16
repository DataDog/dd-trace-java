import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SqlInjectionModule
import org.hibernate.Query
import org.hibernate.ScrollMode
import org.hibernate.Session
import org.hibernate.Transaction

class IastQueryInstrumentationTest extends AbstractHibernateTest {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  private Session session
  private Transaction transaction

  void setup() {
    session = sessionFactory.openSession()
    transaction = session.beginTransaction()
  }

  void cleanup() {
    transaction.commit()
    session.close()
  }

  void 'test sql query [#iterationIndex] #queryString'() {
    given:
    final module = Mock(SqlInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final sqlQuery = session.createSQLQuery(queryString)
    method.call(sqlQuery)

    then:
    1 * module.onJdbcQuery(queryString) >> {
      assert it[0].is(queryString)
    }

    where:
    queryString                                         | method
    'select * from value'                               | { Query query -> query.list() }
    'select * from value where id = 1'                  | { Query query -> query.uniqueResult() }
    'update value set name = \'another\' where id = -1' | { Query query -> query.executeUpdate() }
    'select * from value'                               | { Query query -> query.scroll() }
    'select * from value'                               | { Query query -> query.scroll(ScrollMode.FORWARD_ONLY) }
  }

  void 'test hql query [#iterationIndex] #queryString'() {
    given:
    final module = Mock(SqlInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final hqlQuery = session.createQuery(queryString)
    method.call(hqlQuery)

    then:
    1 * module.onJdbcQuery(queryString) >> {
      assert it[0].is(queryString)
    }

    where:
    queryString                                         | method
    'select v from Value v'                             | { Query query -> query.list() }
    'select v from Value v where v.id = 1'              | { Query query -> query.uniqueResult() }
    'update Value set name = \'another\' where id = -1' | { Query query -> query.executeUpdate() }
    'select v from Value v'                             | { Query query -> query.scroll() }
    'select v from Value v'                             | { Query query -> query.scroll(ScrollMode.FORWARD_ONLY) }
    'select v from Value v'                             | { Query query -> query.iterate() }
  }
}
