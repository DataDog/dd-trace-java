package datadog.trace.instrumentation.javax.mail;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import java.io.IOException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.MimeMultipart;

public class TransportSendAdvice {
  public static void sendAdvice(Message message) {
    EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
    if (message != null) {
      try {
        if (message.getContent() != null) {
          if (message.isMimeType("text/html")) { // simple html
            emailInjectionModule.onSendEmail(message.getContent().toString());
          } else if (message.isMimeType(
              "multipart/*")) { // needs to be converted into single string
            if (message.getContent() instanceof MimeMultipart) {
              emailInjectionModule.onSendEmail(
                  convertPartToString((MimeMultipart) message.getContent()));
            }
          }
        }
      } catch (IOException | MessagingException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static String convertPartToString(MimeMultipart content)
      throws MessagingException, IOException {
    if (content.getCount() == 0) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < content.getCount(); i++) {
      Part part = content.getBodyPart(i);
      if (part.isMimeType("text/html")) { // only concerned with html injection
        sb.append(part.getContent().toString());
      }
    }
    return sb.toString();
  }
}
