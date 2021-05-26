package io.sqreen.testapp.sampleapp

import com.google.common.base.Charsets
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.HttpMessageNotWritableException

class NameAndEmailHttpMessageConverter implements HttpMessageConverter<XXEVulnerabilities.NameAndEmail> {
  @Override
  boolean canRead(Class<?> clazz, MediaType mediaType) {
    clazz == XXEVulnerabilities.NameAndEmail &&
      mediaType.type == 'text' && mediaType.subtype == 'xml'
  }

  @Override
  boolean canWrite(Class<?> clazz, MediaType mediaType) {
    false
  }

  @Override
  List<MediaType> getSupportedMediaTypes() {
    [MediaType.APPLICATION_XML]
  }

  @Override
  XXEVulnerabilities.NameAndEmail read(Class<? extends XXEVulnerabilities.NameAndEmail> clazz,
    HttpInputMessage inputMessage)
  throws IOException, HttpMessageNotReadableException {
    String ct = inputMessage.headers.getFirst('Content-type')
    boolean vulnerable = !ct.contains('vulnerable=false')
    boolean replace = ct.contains("replace_slash=true")
    def variant = 'dom_xerces'
    def matcher = ct =~ /variant=([a-z_-]+)/
    if (matcher.find()) {
      variant = matcher.group(1)
    }

    InputStream is = inputMessage.body
    if (replace) {
      def text = is.text.replaceAll(~/SLASH/, '/')
      is = new ByteArrayInputStream(text.getBytes(Charsets.UTF_8))
    }

    def vuln = new XXEVulnerabilities(vulnerable: vulnerable)
    vuln.parseWithVariant(variant, is)
  }


  @Override
  void write(XXEVulnerabilities.NameAndEmail nameAndEmail, MediaType contentType, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
    throw new HttpMessageNotWritableException()
  }
}
