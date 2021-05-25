package io.sqreen.testapp.sampleapp

import com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl
import com.sun.xml.stream.Constants
import groovy.transform.CompileStatic
import org.dom4j.io.SAXReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import javax.xml.stream.XMLEventReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamException
import javax.xml.stream.events.StartElement
import javax.xml.stream.events.XMLEvent
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathFactory

class XXEVulnerabilities {
  boolean vulnerable = true
  EntityResolver customResolver

  static class NameAndEmail {
    public String name
    public String email
  }

  NameAndEmail parseWithVariant(String variant, InputStream is) {
    if (variant == 'dom_xerces') {
      parseDomXerces(is)
    } else if (variant == 'dom_dom4j') {
      parseDomDom4j(is)
    } else if (variant == 'stax_jdk') {
      parseJDKStax(is)
    } else if (variant == 'stax_woodstox') {
      parseWoodstoxStax(is)
    } else if (variant == 'stax_sjxsp') {
      parseSjxspStax(is)
    } else if (variant == 'jax_xerces') {
      parseJdkXercesSAX(is)
    } else if (variant == 'jax_woodstox') {
      parseWoodstoxSAX(is)
    } else if (variant == 'xpath') {
      parseXPath(is)
    } else {
      throw new RuntimeException('unknown variant')
    }
  }

  NameAndEmail parseDomXerces(stream) {
    NameAndEmail ret = new NameAndEmail()

    DocumentBuilderFactory fac = new com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl()
    fac.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, !vulnerable)
    // despite the name ("external dtd"), it applies also to external entities
    fac.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, vulnerable ? 'all' : '')
    fac.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, vulnerable ? 'all' : '')
    def builder = fac.newDocumentBuilder()
    builder.entityResolver = customResolver
    Document d = builder.parse(stream)
    ret.name = d.getElementsByTagName('name').item(0)?.textContent
    ret.email = d.getElementsByTagName('email').item(0)?.textContent
    ret
  }
  // woodstox's DocumentBuilderFactoryImpl just delegates to another DocumentBuilderFactory

  NameAndEmail parseDomDom4j(InputStream stream) {
    SAXReader reader = new SAXReader()
    reader.setFeature("http://xml.org/sax/features/external-general-entities", vulnerable)
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", vulnerable)
    reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", vulnerable)

    // not needed
    //    reader.includeInternalDTDDeclarations = true
    //    reader.includeExternalDTDDeclarations = true
    if (customResolver) {
      reader.entityResolver = customResolver
    }
    org.dom4j.Document doc = reader.read(stream)

    // string(//email) gives &bar; instead
    new NameAndEmail(name: doc.selectObject('string(//name)'),
    email: doc.selectObject('//email').textTrim)
  }

  @CompileStatic
  private NameAndEmail parseStax(Class c, InputStream stream) {
    NameAndEmail ret = new NameAndEmail()

    XMLInputFactory fac = c.newInstance()
    if (c.name.startsWith('com.sun.xml.internal.stream.')) {
      // affects both external DTD and external entity references
      fac.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, vulnerable ? 'all' : '')
      fac.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, vulnerable ? 'all' : '')
      if (customResolver) {
        fac.setProperty('javax.xml.stream.resolver', customStreamResolver)
      }
    } else if (c.name == 'com.sun.xml.stream.ZephyrParserFactory') {
      // doesn't affect DTD loading
      fac.setProperty(Constants.ZEPHYR_PROPERTY_PREFIX + Constants.IGNORE_EXTERNAL_DTD, !vulnerable)
      fac.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, vulnerable)
      if (customResolver) {
        ((com.sun.xml.stream.ZephyrParserFactory) fac).XMLResolver = customStreamResolver
      }
    } else if (c.name.startsWith('com.fasterxml.aalto.stax')) {
      // aalto doesn't support repalcing
      //            fac.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
    } else { //woodstox
      // does it load the external DTD?
      fac.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, vulnerable)
      if (customResolver) {
        ((com.ctc.wstx.stax.WstxInputFactory) fac).XMLResolver = customStreamResolver
      }
    }
    XMLEventReader reader = fac.createXMLEventReader(stream)
    while (reader.hasNext()) {
      XMLEvent event = reader.nextEvent()
      if (!event.startElement) {
        continue
      }
      StartElement stEl = event.asStartElement()
      if (stEl.name.localPart == 'name') {
        event = reader.nextEvent()
        if (event.isCharacters()) {
          ret.name = event.asCharacters().getData()
        }
      } else if (stEl.name.localPart == 'email') {
        event = reader.nextEvent()
        if (event.isCharacters()) {
          ret.email = event.asCharacters().getData()
        }
      }
    }

    ret
  }

  NameAndEmail parseJDKStax(InputStream is) {
    parseStax com.sun.xml.internal.stream.XMLInputFactoryImpl, is
  }

  NameAndEmail parseWoodstoxStax(InputStream is) {
    parseStax com.ctc.wstx.stax.WstxInputFactory, is
  }

  NameAndEmail parseSjxspStax(InputStream is) {
    parseStax com.sun.xml.stream.ZephyrParserFactory, is
  }

  NameAndEmail parseAaltoStax(InputStream is) {
    parseStax com.fasterxml.aalto.stax.InputFactoryImpl, is
  }

  static class SAXHandler extends DefaultHandler {
    enum State {
      OTHER, IN_NAME, IN_EMAIL
    }

    State state = State.OTHER
    NameAndEmail ret = new NameAndEmail()

    private final Logger logger = LoggerFactory.getLogger(getClass())

    @Override
    void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
      if (qName == 'name') {
        state = State.IN_NAME
      } else if (qName == 'email') {
        state = State.IN_EMAIL
      }
    }

    @Override
    void endElement(String uri, String localName, String qName) throws SAXException {
      state = State.OTHER
    }

    @Override
    void characters(char[] ch, int start, int length) throws SAXException {
      if (state == State.IN_NAME) {
        if (!ret.name) { ret.name = ' ' }
        ret.name += new String(ch, start, length)
      } else if (state == State.IN_EMAIL) {
        if (!ret.email) { ret.email = ' ' }
        ret.email += new String(ch, start, length)
      }
    }

    @Override
    void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) throws SAXException {
      logger.warn("unparsedEntityDecl{name=$name, publicId=$publicId, " +
        "systemId=$systemId, notationName=$notationName}")
    }
  }

  @CompileStatic
  private NameAndEmail parseSAX(Class c, InputStream is) {
    SAXParserFactory fac = c.newInstance()
    if (!c.name.startsWith('com.fasterxml.aalto.')) {
      fac.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, !vulnerable)
    }

    SAXParser parser = fac.newSAXParser()
    XMLReader reader = parser.XMLReader
    def handler = new SAXHandler()
    reader.contentHandler = handler
    if (customResolver) {
      reader.entityResolver = customResolver
    }
    reader.parse(new InputSource(is))

    handler.ret
  }

  NameAndEmail parseJdkXercesSAX(InputStream is) {
    parseSAX com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl, is
  }

  NameAndEmail parseXercesSAX(InputStream is) {
    parseSAX org.apache.xerces.jaxp.SAXParserFactoryImpl, is
  }

  NameAndEmail parseWoodstoxSAX(InputStream is) {
    parseSAX com.ctc.wstx.sax.WstxSAXParserFactory, is
  }

  NameAndEmail parseAaltoSAX(InputStream is) {
    parseSAX com.fasterxml.aalto.sax.SAXParserFactoryImpl, is
  }

  // by default it will use the embedded xerces
  NameAndEmail parseXPath(InputStream is) {
    XPathFactory xpathFactory = XPathFactory.newInstance()
    XPath xpath = xpathFactory.newXPath()
    // no methods for setting attributes
    // the only solution is to pass a safely parsed InputSource to evaluate()
    Closure<InputSource> source
    if (vulnerable) {
      String data = is.text
      source = { -> new InputSource(new StringReader(data)) }
    } else {
      // force jdk impl
      DocumentBuilderFactory df = new DocumentBuilderFactoryImpl()
      df.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, '')
      df.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, '')
      def builder = df.newDocumentBuilder()
      Document doc = builder.parse(is)
      source = { -> doc }
    }
    String name = xpath.evaluate('//name', source())
    String email = xpath.evaluate('//email', source())

    new NameAndEmail(name: name, email: email)
  }

  @Lazy
  private XMLResolver customStreamResolver = new XMLResolver() {
    @Override
    Object resolveEntity(String publicID, String systemID, String baseURI, String namespace) throws XMLStreamException {
      def entity = customResolver.resolveEntity(publicID, systemID)
      if (entity) {
        return entity.byteStream
      }
    }
  }
}
