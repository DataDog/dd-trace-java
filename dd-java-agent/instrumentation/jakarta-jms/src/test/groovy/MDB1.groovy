import jakarta.ejb.MessageDrivenBean
import jakarta.ejb.MessageDrivenContext
import jakarta.jms.Message
import jakarta.jms.MessageListener

public class MDB1 implements MessageDrivenBean, MessageListener {

  public void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }
  public void ejbRemove() {}
  public void setMessageDrivenContext(MessageDrivenContext ctx) {}
}

