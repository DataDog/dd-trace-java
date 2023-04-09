import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring

abstract class GraphQLTest extends VersionedNamingTestBase {
  @Shared
  GraphQL graphql

  private static final long DELAY_IN_HASHID_RESOLVER_MS = 1000

  @Override
  def setup() {
    def reader = new InputStreamReader(
    this.getClass().getClassLoader().getResourceAsStream("schema.graphqls"), StandardCharsets.UTF_8)
    reader.withCloseable {
      def typeRegistry = new SchemaParser().parse(reader)
      def runtimeWiring = RuntimeWiring.newRuntimeWiring()
      .type(newTypeWiring("Query").dataFetcher("bookById", new DataFetcher<Book>() {
        @Trace(operationName = "getBookById", resourceName = "book")
        @Override
        Book get(DataFetchingEnvironment environment) throws Exception {
          String bookId = environment.getArgument("id")
          return Book.getById(bookId)
        }
      }))
      .type(newTypeWiring("Book").dataFetcher("isbn", new DataFetcher<String>() {
        @Override
        String get(DataFetchingEnvironment environment) throws Exception {
          Book book = environment.getSource()
          return book.getIsbn()
        }
      }))
      .type(newTypeWiring("Book").dataFetcher("author", new DataFetcher<Author>() {
        @Override
        Author get(DataFetchingEnvironment environment) throws Exception {
          Book book = environment.getSource()
          String authorId = book.getAuthorId()
          return Author.getById(authorId)
        }
      }))
      .type(newTypeWiring("Book").dataFetcher("cover", new DataFetcher<String>() {
        @Override
        String get(DataFetchingEnvironment environment) throws Exception {
          throw new IllegalStateException("TEST")
        }
      }))
      .type(newTypeWiring("Book").dataFetcher("bookHash", new DataFetcher<CompletableFuture<Integer>>() {
        @Override
        CompletableFuture<Integer> get(DataFetchingEnvironment environment) throws Exception {
          return CompletableFuture.supplyAsync(() -> {
            try {
              Thread.sleep(DELAY_IN_HASHID_RESOLVER_MS)
            }
            catch (InterruptedException ignored) {
            }
            return Integer.valueOf(1231232)
          })
        }
      }))
      .build()
      SchemaGenerator schemaGenerator = new SchemaGenerator()
      GraphQLSchema graphqlSchema = schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring)
      GraphQL.Builder graphqlBuilder = GraphQL.newGraphQL(graphqlSchema)
      this.graphql = graphqlBuilder.build()
    }
  }

  def "successful query produces spans"() {
    setup:
    def query = 'query findBookById {\n' +
    '  bookById(id: "book-1") {\n' +
    '    id #test\n' +
    '    name\n' +
    '    pageCount\n' +
    '    author {\n' +
    '      firstName\n' +
    '      lastName\n' +
    '    }\n' +
    '    isbn\n' +
    '  }\n' +
    '}'
    def expectedQuery = 'query findBookById {\n' +
    '  bookById(id: {String}) {\n' +
    '    id\n' +
    '    name\n' +
    '    pageCount\n' +
    '    author {\n' +
    '      firstName\n' +
    '      lastName\n' +
    '    }\n' +
    '    isbn\n' +
    '  }\n' +
    '}\n'

    ExecutionResult result = graphql.execute(query)

    expect:
    result.getErrors().isEmpty()

    assertTraces(1) {
      trace(7) {
        span {
          operationName operation()
          resourceName "findBookById"
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.source" expectedQuery
            "graphql.operation.name" "findBookById"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Book.isbn"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "ID!"
            "graphql.coordinates" "Book.isbn"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Book.author"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Author"
            "graphql.coordinates" "Book.author"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Query.bookById"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Book"
            "graphql.coordinates" "Query.bookById"
            defaultTags()
          }
        }
        span {
          operationName "getBookById"
          resourceName "book"
          childOf(span(3))
          spanType null
          errored false
          measured false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          operationName "graphql.validation"
          resourceName "graphql.validation"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
        span {
          operationName "graphql.parsing"
          resourceName "graphql.parsing"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
      }
    }
  }

  def "successful query produces spans correctly representing completable future timing"() {
    setup:
    def query = 'query findBookHashById {\n' +
    '  bookById(id: "book-1") {\n' +
    '    bookHash\n' +
    '  }\n' +
    '}'
    def expectedQuery = 'query findBookHashById {\n' +
    '  bookById(id: {String}) {\n' +
    '    bookHash\n' +
    '  }\n' +
    '}\n'

    ExecutionResult result = graphql.execute(query)

    expect:
    result.getErrors().isEmpty()

    assertTraces(1) {
      trace(6) {
        span {
          operationName operation()
          resourceName 'findBookHashById'
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.source" expectedQuery
            "graphql.operation.name" "findBookHashById"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Book.bookHash"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          duration((nanos) -> nanos >= TimeUnit.MILLISECONDS.toNanos(DELAY_IN_HASHID_RESOLVER_MS))
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Int!"
            "graphql.coordinates" "Book.bookHash"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Query.bookById"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Book"
            "graphql.coordinates" "Query.bookById"
            defaultTags()
          }
        }
        span {
          operationName "getBookById"
          resourceName "book"
          childOf(span(2))
          spanType null
          errored false
          measured false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          operationName "graphql.validation"
          resourceName "graphql.validation"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
        span {
          operationName "graphql.parsing"
          resourceName "graphql.parsing"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
      }
    }
  }

  def "query validation error"() {
    setup:
    def query = 'query findBookById {\n' +
    '  bookById(id: "book-1") {\n' +
    '    id #test\n' +
    '    title\n' + // field doesn't exist
    '    color\n' + // field doesn't exist
    '  }\n' +
    '}'
    def expectedQuery = 'query findBookById {\n' +
    '  bookById(id: {String}) {\n' +
    '    id\n' +
    '    title\n' +
    '    color\n' +
    '  }\n' +
    '}\n'
    ExecutionResult result =
    graphql.execute(query)

    expect:
    !result.getErrors().isEmpty()

    assertTraces(1) {
      trace(3) {
        span {
          operationName operation()
          resourceName operation()
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.source" expectedQuery
            "graphql.operation.name" null
            "error.message" { it.contains("Field 'title' in type 'Book' is undefined") }
            "error.message" { it.contains("(and 1 more errors)") }
            defaultTags()
          }
        }
        span {
          operationName "graphql.validation"
          resourceName "graphql.validation"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
        span {
          operationName "graphql.parsing"
          resourceName "graphql.parsing"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
      }
    }
  }

  def "query parse error"() {
    setup:
    def query = 'query findBookById {\n' +
    '  bookById(id: "book-1")) {\n' + // double closing brace
    '    id #test\n' +
    '  }\n' +
    '}'
    ExecutionResult result =
    graphql.execute(query)

    expect:
    !result.getErrors().isEmpty()

    assertTraces(1) {
      trace(2) {
        span {
          operationName operation()
          resourceName operation()
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.source" query
            "graphql.operation.name" null
            "error.message" { it.toLowerCase().startsWith("invalid syntax") }
            defaultTags()
          }
        }
        span {
          operationName "graphql.parsing"
          resourceName "graphql.parsing"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "error.type" "graphql.parser.InvalidSyntaxException"
            "error.message" { it.toLowerCase().startsWith("invalid syntax") }
            "error.stack" String
            defaultTags()
          }
        }
      }
    }
  }

  def "query fetch error"() {
    setup:
    def query = 'query findBookById {\n' +
    '  bookById(id: "book-1") {\n' +
    '    id #test\n' +
    '    cover\n' + // throws an exception when fetched
    '  }\n' +
    '}'
    def expectedQuery = 'query findBookById {\n' +
    '  bookById(id: {String}) {\n' +
    '    id\n' +
    '    cover\n' +
    '  }\n' +
    '}\n'
    ExecutionResult result = graphql.execute(query)

    expect:
    !result.getErrors().isEmpty()

    assertTraces(1) {
      trace(6) {
        span {
          operationName operation()
          resourceName "findBookById"
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.source" expectedQuery
            "graphql.operation.name" "findBookById"
            "error.message" "Exception while fetching data (/bookById/cover) : TEST"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Book.cover"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "String"
            "graphql.coordinates" "Book.cover"
            "error.type" "java.lang.IllegalStateException"
            "error.message" "TEST"
            "error.stack" String
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "Query.bookById"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Book"
            "graphql.coordinates" "Query.bookById"
            defaultTags()
          }
        }
        span {
          operationName "getBookById"
          resourceName "book"
          childOf(span(2))
          spanType null
          errored false
          measured false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          operationName "graphql.validation"
          resourceName "graphql.validation"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
        span {
          operationName "graphql.parsing"
          resourceName "graphql.parsing"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            defaultTags()
          }
        }
      }
    }
  }
}

@Flaky
class GraphQLV0ForkedTest extends GraphQLTest {

  @Override
  int version() {
    0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "graphql.request"
  }
}

@Flaky
class GraphQLV1ForkedTest extends GraphQLTest {

  @Override
  int version() {
    1
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "graphql.server.request"
  }
}
