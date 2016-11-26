package translator;

import bankws.BankWS;
import bankws.InterestRateService;
import com.rabbitmq.client.AMQP.*;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import connector.RabbitMQConnector;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.ws.WebServiceRef;
import jdk.nashorn.internal.objects.NativeArray;
import models.Data;

/**
 *
 * @author Tomoe
 */
public class WSTranslator {

    private final RabbitMQConnector connector = new RabbitMQConnector();
    @WebServiceRef(wsdlLocation
            = "http://localhost:8080/BankWS/InterestRateService?WSDL")
    static InterestRateService service;

    private Channel channel;
    private String queueName;
    private final String EXCHANGENAME = "whatTranslator";
    private final String REPLYTOQUENAME = "whatNormalizerQueue";
    private final String ROUTING_KEY="ws";

    public void init() throws IOException {
        channel = connector.getChannel();
        channel.exchangeDeclare(EXCHANGENAME, "direct");
        queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, EXCHANGENAME, ROUTING_KEY);
        service = new InterestRateService();
        receive();
    }

    public boolean receive() throws IOException {

        System.out.println(" [*] Waiting for messages.");
        final Consumer consumer = new DefaultConsumer(channel) {

            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {

                System.out.println(" [x] Received ");
                try {
                    send(properties, body);
                }
                catch (ClassNotFoundException ex) {
                    Logger.getLogger(WSTranslator.class.getName()).log(Level.SEVERE, null, ex);
                }
                catch (JAXBException ex) {
                    Logger.getLogger(WSTranslator.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        };
        channel.basicConsume(queueName, true, consumer);
        return true;
    }

    private Data unmarchal(String bodyString) throws JAXBException {
        JAXBContext jc = JAXBContext.newInstance(Data.class);
        Unmarshaller unmarchaller = jc.createUnmarshaller();
        StringReader reader = new StringReader(bodyString);
        return (Data) unmarchaller.unmarshal(reader);
    }

    private String removeBom(String xmlString) {
        String res = xmlString.trim();
        return res.substring(res.indexOf("<?xml"));
    }

    public boolean send(BasicProperties prop, byte[] body) throws IOException, ClassNotFoundException, JAXBException {
        String bodyString = removeBom(new String(body));
        Data data = unmarchal(bodyString);
        Map<String, Object> headers = prop.getHeaders();
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            System.out.println("key "+ key );
             System.out.println("value "+ value );
            
        }

        String corrId = prop.getCorrelationId();
        String bankName = headers.get("bankName").toString();
        int total = (int) headers.get("total");
        int messageNo = (int) headers.get("messageNo");
        String rep = getInterestRate(data.getSsn(), data.getCreditScore(), data.getLoanAmount(), data.getLoanDuration(), REPLYTOQUENAME,
                corrId, bankName, total, messageNo);
        System.out.println(rep);
        return true;

    }

    private static String getInterestRate(String ssn, int creditScore, double loanAmount, int loanDuration, String replyTo,
            String corrId, String bankName, int total, int messageNo) {
        try {
            BankWS port = service.getBankWSPort();
            return port.getInterestRate(ssn, creditScore, loanAmount, loanDuration, replyTo, corrId, bankName, total, messageNo);

        }
        catch (Exception ex) {
            System.out.println("getInterestRate " + ex.getMessage());
            return "failed";
        }
    }
}
