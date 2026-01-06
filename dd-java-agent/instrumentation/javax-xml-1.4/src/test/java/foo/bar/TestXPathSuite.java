package foo.bar;

import javax.xml.namespace.QName;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import org.xml.sax.InputSource;

public class TestXPathSuite {

  private final XPath xp;

  public TestXPathSuite(final XPath xp) {
    this.xp = xp;
  }

  public void compile(final String expression) throws XPathExpressionException {
    xp.compile(expression);
  }

  public void evaluate(final String expression, final InputSource source)
      throws XPathExpressionException {
    xp.evaluate(expression, source);
  }

  public void evaluate(final String expression, final InputSource source, final QName returnType)
      throws XPathExpressionException {
    xp.evaluate(expression, source, returnType);
  }

  public void evaluate(final String expression, final Object item) throws XPathExpressionException {
    xp.evaluate(expression, item);
  }

  public void evaluate(final String expression, final Object item, final QName returnType)
      throws XPathExpressionException {
    xp.evaluate(expression, item, returnType);
  }
}
