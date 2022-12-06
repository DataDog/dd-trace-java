package datadog.trace.instrumentation.graphqljava;

import graphql.language.AstPrinter;
import graphql.language.AstTransformer;
import graphql.language.BooleanValue;
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

public final class GraphQLQuerySanitizer extends NodeVisitorStub {
  private static final NodeVisitor REPLACE_VALUES_VISITOR = new GraphQLQuerySanitizer();
  private static final AstTransformer AST_TRANSFORMER = new AstTransformer();

  public static String sanitizeQuery(Node<?> node) {
    Node sanitizedQuery = AST_TRANSFORMER.transform(node, REPLACE_VALUES_VISITOR);
    return AstPrinter.printAst(sanitizedQuery);
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
