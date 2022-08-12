package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.language.AstPrinter;
import graphql.language.AstTransformer;
import graphql.language.BooleanValue;
import graphql.language.Document;
import graphql.language.EnumValue;
import graphql.language.Node;
import graphql.language.NodeVisitor;
import graphql.language.NodeVisitorStub;
import graphql.language.NullValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

public class ParsingInstrumentationContext extends SimpleInstrumentationContext<Document> {

  private final AgentSpan parsingSpan;
  private final GraphQLInstrumentation.State state;
  private final String rawQuery;

  public ParsingInstrumentationContext(
      AgentSpan parsingSpan, GraphQLInstrumentation.State state, String rawQuery) {
    this.parsingSpan = parsingSpan;
    this.state = state;
    this.rawQuery = rawQuery;
  }

  @Override
  public void onCompleted(Document result, Throwable t) {
    if (t != null) {
      DECORATE.onError(parsingSpan, t);
      // fail to parse query use the original raw query
      state.setQuery(rawQuery);
    } else {
      // parse successfully use sanitized query
      state.setQuery(AstPrinter.printAst(ReplaceValuesVisitor.replaceValues(result)));
    }
    DECORATE.beforeFinish(parsingSpan);
    parsingSpan.finish();
  }

  private static final class ReplaceValuesVisitor extends NodeVisitorStub {
    private static final NodeVisitor ReplaceValuesVisitor = new ReplaceValuesVisitor();
    private static final AstTransformer astTransformer = new AstTransformer();

    public static Node<?> replaceValues(Node<?> node) {
      return astTransformer.transform(node, ReplaceValuesVisitor);
    }

    @Override
    protected TraversalControl visitValue(Value<?> node, TraverserContext<Node> context) {
      EnumValue newValue = new EnumValue("?");
      return TreeTransformerUtil.changeNode(context, newValue);
    }

    @Override
    public TraversalControl visitVariableReference(
        VariableReference node, TraverserContext<Node> context) {
      return super.visitValue(node, context);
    }

    @Override
    public TraversalControl visitBooleanValue(BooleanValue node, TraverserContext<Node> context) {
      return super.visitValue(node, context);
    }

    @Override
    public TraversalControl visitNullValue(NullValue node, TraverserContext<Node> context) {
      return super.visitValue(node, context);
    }
  }
}
