import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.graphqljava.GraphQLQuerySanitizer
import graphql.parser.Parser

class GraphQLQuerySanitizerTest extends InstrumentationSpecification {
  private Parser parser = new Parser()

  def "sanitizes the use of a String literal in query"() {
    when:
    def parsedDoc = parser.parse(
      'query findBookById {\n' +
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
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'query findBookById {\n' +
      '  bookById(id: {String}) {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }

  def "sanitizes the use of an array of String literals in query"() {
    when:
    def parsedDoc = parser.parse(
      'query findBooksByIds {\n' +
      '  booksByIds(ids: ["book-1","book-2","book-3"]) {\n' +
      '    id #test\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'query findBooksByIds {\n' +
      '  booksByIds(ids: [{String}, {String}, {String}]) {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }

  def "sanitizes the use of a Int literal in query"() {
    when:
    def parsedDoc = parser.parse(
      'query findBookById {\n' +
      '  bookById(id: 5) {\n' +
      '    id #test\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'query findBookById {\n' +
      '  bookById(id: {Int}) {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }

  def "sanitizes the use of a Float literal in query"() {
    when:
    def parsedDoc = parser.parse(
      'query findCheapBooks {\n' +
      '  booksCheaperThan(price: 15.99) {\n' +
      '    id #test\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'query findCheapBooks {\n' +
      '  booksCheaperThan(price: {Float}) {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }

  def "sanitizes the use of a Boolean literal in query"() {
    when:
    def parsedDoc = parser.parse(
      'query findCheapBooks {\n' +
      '  findBooks(isCheap: true) {\n' +
      '    id #test\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'query findCheapBooks {\n' +
      '  findBooks(isCheap: {Boolean}) {\n' +
      '    id\n' +
      '    name\n' +
      '    pageCount\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }

  def "sanitizes literals in mutation nested input"() {
    when:
    def parsedDoc = parser.parse(
      'mutation testUpdateBookAuthor{\n' +
      '  updateBookAuthor(input: {bookId:2, author:{firstName: "Irma", lastName: "K"}}) {\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'mutation testUpdateBookAuthor {\n' +
      '  updateBookAuthor(input: {bookId : {Int}, author : {firstName : {String}, lastName : {String}}}) {\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }

  def "sanitization leaves mutation variable reference alone"() {
    when:
    def parsedDoc = parser.parse(
      'mutation testUpdateBookAuthor($id: ID!, $authorFirst: String!, $authorLast: String!) {\n' +
      '  updateBookAuthor(input: {bookId:$id, author:{firstName: $authorFirst, lastName: $authorLast}}) {\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}'
      )

    then:
    GraphQLQuerySanitizer.sanitizeQuery(parsedDoc) ==
      'mutation testUpdateBookAuthor($id: ID!, $authorFirst: String!, $authorLast: String!) {\n' +
      '  updateBookAuthor(input: {bookId : $id, author : {firstName : $authorFirst, lastName : $authorLast}}) {\n' +
      '    author {\n' +
      '      firstName\n' +
      '      lastName\n' +
      '    }\n' +
      '  }\n' +
      '}\n'
  }
}
