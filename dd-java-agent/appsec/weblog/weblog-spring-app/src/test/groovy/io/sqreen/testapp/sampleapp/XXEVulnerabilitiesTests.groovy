package io.sqreen.testapp.sampleapp

import com.ctc.wstx.exc.WstxParsingException
import com.google.common.base.Charsets
import com.google.common.io.CharSource
import org.junit.Test
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

import javax.xml.stream.XMLStreamException

import static groovy.test.GroovyAssert.shouldFail
import static io.sqreen.testapp.sampleapp.XXEVulnerabilities.NameAndEmail
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.*
import static org.junit.Assume.assumeThat

class XXEVulnerabilitiesTests {

  /*
   * sample.dtd:
   * <?xml version="1.0" encoding="UTF-8"?>
   * <!ENTITY foo "foo value">
   *
   * sample.txt:
   * foo@bar.com\n
   */

  boolean vulnerable = true
  def entityResolver

  @Lazy
  XXEVulnerabilities vuln = new XXEVulnerabilities(
  vulnerable: vulnerable, customResolver: entityResolver)

  /* XML file with external references */
  private static final String BAD_XML = '''
        <!DOCTYPE data SYSTEM "http://sqreen-ci-java.s3.amazonaws.com/xxe/sample.dtd" [
            <!ENTITY bar SYSTEM "http://sqreen-ci-java.s3.amazonaws.com/xxe/sample.txt">
        ]>
        <data>
            <name>&foo;</name>
            <email>&bar;</email>
        </data>
    '''

  @Lazy
  private EntityResolver customEntityResolver = new EntityResolver() {
    @Override
    InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
      if (systemId.endsWith('sample.dtd')) {
        InputStream stream = XXEVulnerabilitiesTests.classLoader.getResourceAsStream('xxe/sample.dtd')
        return new InputSource(stream)
      } else if (systemId.endsWith('sample.txt')) {
        InputStream stream = XXEVulnerabilitiesTests.classLoader.getResourceAsStream('xxe/sample.txt')
        return new InputSource(stream)
      }
    }
  }

  /* this is one is identical except the references are local
   * TODO: tests with this */
  // private static final URL LOCAL_XML_FILE = XXEVulnerabilitiesTests.classLoader.getResource('local.xml')

  InputStream getIs() {
    CharSource.wrap(BAD_XML).asByteSource(Charsets.UTF_8).openStream()
  }

  @Test
  void 'parse document with JDK xerces dom'() {
    NameAndEmail result = vuln.parseDomXerces(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with JDK xerces dom - not vulnerable'() {
    vulnerable = false
    shouldFail(SAXParseException) { vuln.parseDomXerces(is) }
  }

  @Test
  void 'parse document with JDK xerces dom - entity resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseDomXerces(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with dom4j'() {
    assumeThat System.getProperty('java.vm.vendor'), not('IBM Corporation')

    NameAndEmail result = vuln.parseDomDom4j(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with dom4j - not vulnerable'() {
    vulnerable = false
    NameAndEmail result = vuln.parseDomDom4j(is)
    assertThat result.name, is('')
    assertThat result.email, is('')
  }

  @Test
  void 'parse document with dom4j - entity resolver'() {
    assumeThat System.getProperty('java.vm.vendor'), not('IBM Corporation')

    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseDomDom4j(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with JDK stax'() {
    // resolution is also Xerces based, at least in modern JDKs
    NameAndEmail result = vuln.parseJDKStax(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with JDK stax - not vulnerable'() {
    vulnerable = false
    // resolution is also Xerces based, at least in modern JDKs
    shouldFail(XMLStreamException) { vuln.parseJDKStax(is) }
  }

  @Test
  void 'parse document with JDK stax - entity resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseJDKStax(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with Woodstox stax'() {
    NameAndEmail result = vuln.parseWoodstoxStax(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with Woodstox stax - not vulnerable'() {
    vulnerable = false
    shouldFail(WstxParsingException) { vuln.parseWoodstoxStax(is) }
  }

  @Test
  void 'parse document with Woodstox stax - entity resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseWoodstoxStax(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with Zephyr stax'() {
    NameAndEmail result = vuln.parseSjxspStax(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with Zephyr stax - not vulnerable'() {
    vulnerable = false
    def res = vuln.parseSjxspStax(is)
    assertThat res.name, is(nullValue())
    assertThat res.email, is(nullValue())
  }

  @Test
  void 'parse document with Zephyr stax - entity resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseSjxspStax(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with Aalto stax'() {
    // it doesn't support resolving the entities - so not vulnerable
    shouldFail(com.fasterxml.aalto.WFCException) { vuln.parseAaltoStax(is) }
  }

  @Test
  void 'parse document with Jdk Xerces SAX'() {
    NameAndEmail result = vuln.parseJdkXercesSAX(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with Jdk Xerces SAX - not vulnerable'() {
    assumeThat System.getProperty('java.vm.vendor'), not('IBM Corporation')

    vulnerable = false
    shouldFail(SAXParseException) { vuln.parseJdkXercesSAX(is) }
  }

  @Test
  void 'parse document with Jdk Xerces SAX - custom resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseJdkXercesSAX(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with Xerces SAX'() {
    NameAndEmail result = vuln.parseXercesSAX(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with Xerces SAX - custom resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseXercesSAX(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with Woodstox SAX'() {
    NameAndEmail result = vuln.parseWoodstoxSAX(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with Woodstox SAX - not vulnerable'() {
    vulnerable = false
    shouldFail(SAXParseException) { vuln.parseWoodstoxSAX(is) }
  }

  @Test
  void 'parse document with Woodstox SAX - entity resolver'() {
    entityResolver = customEntityResolver
    NameAndEmail result = vuln.parseWoodstoxSAX(is)
    nameAndValueWereExpandedLocal result
  }

  @Test
  void 'parse document with Aalto SAX'() {
    // resolution of entities not supported: so not vulnerable
    shouldFail(SAXParseException) { vuln.parseAaltoSAX(is) }
  }

  @Test
  void 'parse document with XPath'() {
    NameAndEmail result = vuln.parseXPath(is)
    nameAndValueWereExpanded result
  }

  @Test
  void 'parse document with XPath - not vulnerable'() {
    vulnerable = false
    shouldFail(SAXParseException) { vuln.parseXPath(is) }
  }

  private void nameAndValueWereExpanded(NameAndEmail result) {
    assertThat result.name?.trim(), is('foo value')
    assertThat result.email?.trim(), containsString('foo@bar.com')
  }

  private void nameAndValueWereExpandedLocal(NameAndEmail result) {
    assertThat result.name?.trim(), is('fuu value')
    assertThat result.email?.trim(), containsString('foo@baz.com')
  }
}
