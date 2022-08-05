import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Test

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
            // TODO maybe created a span?
            return Author.getById(authorId)
          }
        }))
        .build()
      SchemaGenerator schemaGenerator = new SchemaGenerator()
      GraphQLSchema graphqlSchema = schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring)
      GraphQL.Builder graphqlBuilder = GraphQL.newGraphQL(graphqlSchema)
      this.graphql = graphqlBuilder.build()
    }
  }

  @Test
  def "successful query produces spans"() {
    setup:
    def query = 'query findBookById {\n' +
      '  bookById(id: "book-1") {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
    ExecutionResult result =
      graphql.execute(query)

    expect:
    result.getErrors().isEmpty()

    assertTraces(1) {
      trace(5) {
        span {
          operationName "query findBookById"
          resourceName "query findBookById"
          spanType DDSpanTypes.GRAPHQL
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "graphql-java"
            "graphql.query" query
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

  //TODO test errors
}
