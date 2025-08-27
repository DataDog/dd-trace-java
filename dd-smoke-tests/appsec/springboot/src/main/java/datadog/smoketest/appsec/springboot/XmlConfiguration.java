package datadog.smoketest.appsec.springboot;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.w3c.dom.Document;

@Configuration
public class XmlConfiguration implements WebMvcConfigurer {

  @Override
  public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.add(new DocumentHttpMessageConverter());
  }

  /**
   * Custom HttpMessageConverter to handle DOM Document objects for XML requests/responses. This
   * enables Spring MVC to properly serialize/deserialize Document objects, which will trigger our
   * ObjectIntrospection XML DOM parsing in the instrumentation.
   */
  public static class DocumentHttpMessageConverter implements HttpMessageConverter<Document> {

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
      return Document.class.isAssignableFrom(clazz) && isXmlMediaType(mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
      return Document.class.isAssignableFrom(clazz) && isXmlMediaType(mediaType);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
      return Arrays.asList(
          MediaType.APPLICATION_XML, MediaType.TEXT_XML, new MediaType("application", "*+xml"));
    }

    @Override
    public Document read(Class<? extends Document> clazz, HttpInputMessage inputMessage)
        throws IOException, HttpMessageNotReadableException {
      try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputMessage.getBody());
      } catch (Exception e) {
        throw new HttpMessageNotReadableException("Could not parse XML document", e, inputMessage);
      }
    }

    @Override
    public void write(Document document, MediaType contentType, HttpOutputMessage outputMessage)
        throws IOException, HttpMessageNotWritableException {
      try {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));

        String xmlString = writer.toString();
        outputMessage.getBody().write(xmlString.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new HttpMessageNotWritableException("Could not write XML document", e);
      }
    }

    private boolean isXmlMediaType(MediaType mediaType) {
      return mediaType != null
          && (MediaType.APPLICATION_XML.isCompatibleWith(mediaType)
              || MediaType.TEXT_XML.isCompatibleWith(mediaType)
              || (mediaType.getSubtype() != null && mediaType.getSubtype().endsWith("+xml")));
    }
  }
}
