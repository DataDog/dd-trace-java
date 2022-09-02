import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser

import java.nio.charset.StandardCharsets

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring

class GraphQLTest extends AgentTestRunner {
  private GraphQL graphql

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
      '  }\n' +
      '}'
    def expectedQuery = 'query findBookById {\n' +
      '  bookById(id: ?) {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'

    ExecutionResult result = graphql.execute(query)

    expect:
    result.getErrors().isEmpty()

    assertTraces(1) {
      trace(6) {
        span {
          operationName "graphql.request"
          resourceName "graphql.request"
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.query" expectedQuery
            "graphql.operation.name" "findBookById"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "graphql.field"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Author"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "graphql.field"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Book"
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
      '  bookById(id: ?) {\n' +
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
          operationName "graphql.request"
          resourceName "graphql.request"
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.query" expectedQuery
            "graphql.operation.name" null
            "error.msg" { it.contains("Field 'title' in type 'Book' is undefined") }
            "error.msg" { it.contains("(and 1 more errors)") }
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
          operationName "graphql.request"
          resourceName "graphql.request"
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.query" query
            "graphql.operation.name" null
            "error.msg" "Invalid Syntax : offending token ')' at line 2 column 25"
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
            "error.msg" "Invalid Syntax : offending token ')' at line 2 column 25"
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
      '  bookById(id: ?) {\n' +
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
          operationName "graphql.request"
          resourceName "graphql.request"
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.query" expectedQuery
            "graphql.operation.name" "findBookById"
            "error.msg" "Exception while fetching data (/bookById/cover) : TEST"
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "graphql.field"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "String"
            "error.type" "java.lang.IllegalStateException"
            "error.msg" "TEST"
            "error.stack" String
            defaultTags()
          }
        }
        span {
          operationName "graphql.field"
          resourceName "graphql.field"
          childOf(span(0))
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.type" "Book"
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
