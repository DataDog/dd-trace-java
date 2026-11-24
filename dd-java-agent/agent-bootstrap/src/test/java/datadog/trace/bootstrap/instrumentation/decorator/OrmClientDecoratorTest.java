package datadog.trace.bootstrap.instrumentation.decorator;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.function.Function;
import org.tabletest.junit.TableTest;

class OrmClientDecoratorTest extends DatabaseClientDecoratorTest {

  @TableTest({
    "scenario        | entity   | entityName | isSet",
    "null entity     |          | name       | false",
    "null entityName | not null |            | false",
    "name set        | not null | name       | true "
  })
  void testOnOperation(String entity, String entityName, boolean isSet) {
    OrmClientDecorator decorator = newDecorator(ignored -> entityName);

    // when
    decorator.onOperation(span, entity);

    // then
    if (isSet) {
      verify(span).setResourceName(entityName);
    }
    verifyNoMoreInteractions(span);
  }

  protected OrmClientDecorator newDecorator(Function<Object, String> name) {
    return new OrmClientDecorator() {
      @Override
      public String entityName(Object entity) {
        return name.apply(entity);
      }

      @Override
      protected String dbType() {
        return "test-db";
      }

      @Override
      protected String dbUser(Object o) {
        return "test-user";
      }

      @Override
      protected String dbInstance(Object o) {
        return "test-user";
      }

      @Override
      protected String dbHostname(Object o) {
        return "test-hostname";
      }

      @Override
      protected String service() {
        return "test-service";
      }

      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1"};
      }

      @Override
      protected CharSequence spanType() {
        return "test-type";
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }
    };
  }
}
