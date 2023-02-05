package datadog.trace.instrumentation.graphqljava;

import graphql.language.ArrayValue;
import graphql.language.AstPrinter;
import graphql.language.AstTransformer;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.Node;
import graphql.language.NodeVisitor;
import graphql.language.NodeVisitorStub;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;

public final class GraphQLQuerySanitizer extends NodeVisitorStub {
  private static final NodeVisitor REPLACE_VALUES_VISITOR = new GraphQLQuerySanitizer();
  private static final AstTransformer AST_TRANSFORMER = new AstTransformer();

  public static String sanitizeQuery(Node<?> node) {
    Node sanitizedQuery = AST_TRANSFORMER.transform(node, REPLACE_VALUES_VISITOR);
    return AstPrinter.printAst(sanitizedQuery);
  }

  private static final IntValue sanitizedIntValue = new IntValue(BigInteger.ZERO);
  private static final FloatValue sanitizedFloatValue = new FloatValue(BigDecimal.ZERO);
  private static final StringValue sanitizedStringValue = new StringValue("");
  private static final ObjectValue sanitizedObjectValue = new ObjectValue(Collections.EMPTY_LIST);
  private static final ArrayValue santitizedArrayValue = new ArrayValue(Collections.EMPTY_LIST);

  @Override
  public TraversalControl visitIntValue(IntValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, sanitizedIntValue);
  }

  @Override
  public TraversalControl visitFloatValue(FloatValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, sanitizedFloatValue);
  }

  @Override
  public TraversalControl visitStringValue(StringValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, sanitizedStringValue);
  }

  @Override
  public TraversalControl visitObjectValue(ObjectValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, sanitizedObjectValue);
  }

  @Override
  public TraversalControl visitArrayValue(ArrayValue node, TraverserContext<Node> context) {
    return TreeTransformerUtil.changeNode(context, santitizedArrayValue);
  }
}
