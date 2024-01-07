import javax.ejb.MessageDrivenBean;
import javax.ejb.MessageDrivenContext;
import javax.jms.Message;
import javax.jms.MessageListener;

public class MDB1 implements MessageDrivenBean, MessageListener {

  public void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }

	public void ejbRemove() {}
	public void setMessageDrivenContext(MessageDrivenContext ctx) {}
}

