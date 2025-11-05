import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.NoContentException
import javax.ws.rs.ext.MessageBodyReader
import java.lang.annotation.Annotation
import java.lang.reflect.Type
import java.util.regex.Matcher

class TestMessageBodyReader implements MessageBodyReader<ClassToConvertBodyTo> {
  @Override
  boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
    type == ClassToConvertBodyTo && mediaType.isCompatible(MediaType.APPLICATION_JSON_TYPE)
  }

  @Override
  ClassToConvertBodyTo readFrom(Class<ClassToConvertBodyTo> type,
    Type genericType, Annotation[] annotations,
    MediaType mediaType,
    MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
    Matcher matcher = entityStream.text =~ /"a"\s*:\s*"([^"]+)"/
    if (matcher.find()) {
      return new ClassToConvertBodyTo(a: matcher.group(1))
    }
    throw new NoContentException('unexpected input')
  }
}

