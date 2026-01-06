package datadog.trace.instrumentation.javax.xml

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XPathInjectionModule
import foo.bar.TestXPathSuite
import org.xml.sax.InputSource

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class XPathInstrumentationTest extends InstrumentationSpecification {

  private static final String XML_STRING = """<?xml version="1.0" encoding="UTF-8"?>
<bookstore>

<book category="cooking">
  <title lang="en">Everyday Italian</title>
  <author>Giada De Laurentiis</author>
  <year>2005</year>
  <price>30.00</price>
</book>

<book category="children">
  <title lang="en">Harry Potter</title>
  <author>J K. Rowling</author>
  <year>2005</year>
  <price>29.99</price>
</book>

<book category="web">
  <title lang="en">XQuery Kick Start</title>
  <author>James McGovern</author>
  <author>Per Bothner</author>
  <author>Kurt Cagle</author>
  <author>James Linn</author>
  <author>Vaidyanathan Nagarajan</author>
  <year>2003</year>
  <price>49.99</price>
</book>

<book category="web">
  <title lang="en">Learning XML</title>
  <author>Erik T. Ray</author>
  <year>2003</year>
  <price>39.95</price>
</book>

</bookstore>
"""

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'compile expression calls module onExpression method'() {
    setup:
    final module = Mock(XPathInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final xp = XPathFactory.newInstance(XPathFactory.DEFAULT_OBJECT_MODEL_URI, clazz, this.class.getClassLoader()).newXPath()
    final expression = '/bookstore/book[price>35]/price'

    when:
    new TestXPathSuite(xp).compile(expression)

    then:
    1 * module.onExpression(expression)
    0 * _

    where:
    clazz                                                     | _
    'com.sun.org.apache.xpath.internal.jaxp.XPathFactoryImpl' | _
    'org.apache.xpath.jaxp.XPathFactoryImpl'                  | _
  }

  void 'evaluate expression calls module onExpression method'(String clazz) {
    setup:
    final module = Mock(XPathInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final expression = '/bookstore/book[price>35]/price'
    final doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(XML_STRING)))
    final xp = XPathFactory.newInstance(XPathFactory.DEFAULT_OBJECT_MODEL_URI, clazz, this.class.getClassLoader()).newXPath()
    final suite = new TestXPathSuite(xp)

    when:
    suite.evaluate(expression, doc.getDocumentElement(), XPathConstants.NODESET)

    then:
    1 * module.onExpression(expression)
    0 * _


    when:
    suite.evaluate(expression, doc.getDocumentElement())

    then:
    1 * module.onExpression(expression)
    0 * _


    when:
    suite.evaluate(expression, new InputSource(new StringReader(XML_STRING)), XPathConstants.NODESET)

    then:
    1 * module.onExpression(expression)
    0 * _

    when:
    suite.evaluate(expression, new InputSource(new StringReader(XML_STRING)))

    then:
    1 * module.onExpression(expression)
    0 * _

    where:
    clazz                                                     | _
    'com.sun.org.apache.xpath.internal.jaxp.XPathFactoryImpl' | _
    'org.apache.xpath.jaxp.XPathFactoryImpl'                  | _
  }
}
