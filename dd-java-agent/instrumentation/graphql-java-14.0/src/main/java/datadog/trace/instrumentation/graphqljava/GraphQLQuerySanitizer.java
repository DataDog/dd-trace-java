package datadog.trace.instrumentation.graphqljava;

import graphql.language.AstPrinter;
import graphql.language.AstTransformer;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.Node;
import graphql.language.NodeVisitor;
import graphql.language.NodeVisitorStub;
import graphql.language.StringValue;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

public final class GraphQLQuerySanitizer extends NodeVisitorStub {
  private static final NodeVisitor REPLACE_VALUES_VISITOR = new GraphQLQuerySanitizer();
  private static final AstTransformer AST_TRANSFORMER = new AstTransformer();
  private static final EnumValue enumValueInt = new EnumValue("{Int}");
  private static final EnumValue enumValueString = new EnumValue("{String}");
  private static final EnumValue enumValueFloat = new EnumValue("{Float}");
  private static final EnumValue enumValueBoolean = new EnumValue("{Boolean}");

  public static String sanitizeQuery(Node<?> node) {
    Node sanitizedQuery = AST_TRANSFORMER.transform(node, REPLACE_VALUES_VISITOR);
    return AstPrinter.printAst(sanitizedQuery);
  }

  @Override
  public TraversalControl visitIntValue(IntValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, enumValueInt);
  }

  @Override
  public TraversalControl visitBooleanValue(BooleanValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, enumValueBoolean);
  }

  @Override
  public TraversalControl visitFloatValue(FloatValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, enumValueFloat);
  }

  @Override
  public TraversalControl visitStringValue(StringValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, enumValueString);
  }

}
