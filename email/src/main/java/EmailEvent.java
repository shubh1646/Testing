import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PutItemOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.UUID;

public class EmailEvent implements RequestHandler<SNSEvent, Object> {
    static final String DOMAIN = System.getenv("domain");

    static final String FROM = "admin@"+DOMAIN;

    // The subject line for the email.
    static final String SUBJECT = "Password Reset";

    // The HTML body for the email.
    static final String BODY = "<h1>AWS Library Management System</h1>"
            + "<h3>Actioned required</h3>"
            + "<p>You are receiving this email in response to your password reset request "
            + "for your AWS Library Management Account with LoginId: ";

    static final AmazonDynamoDB DYNAMO_DB = AmazonDynamoDBClientBuilder.defaultClient();

    static final Calendar CALENDAR = Calendar.getInstance();

    public void sendEmail(String email, String token) throws Exception{
        AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(Regions.US_WEST_1).build();
        String content = BODY+email+"</p><p>Link to reset password: "+
                "<a href=\"http://"+DOMAIN+"/reset?email="+email+"&token="+token+"\"/></p>";
        SendEmailRequest request = new SendEmailRequest().withDestination(new Destination().withToAddresses(email))
                .withMessage(new Message()
                        .withBody(new Body()
                                .withHtml(new Content().withCharset("UTF-8").withData(content)))
//                                .withText(new Content()
//                                        .withCharset("UTF-8").withData(TEXTBODY+email+" \nLink to reset password"+
//                                                "<a href=\"http://"+DOMAIN+"/reset?email="+email+"&token="+token+"\"/>")))
                        .withSubject(new Content().withCharset("UTF-8").withData(SUBJECT)))
                .withSource(FROM);
        client.sendEmail(request);
    }

    public void putItem(String email) {
        DynamoDB dynamoDB = new DynamoDB(DYNAMO_DB);
        Table table = dynamoDB.getTable("csye6225");
        Item item = new Item()
                .withPrimaryKey("username", email)
                .withString("token", UUID.randomUUID().toString())
                .withNumber("timeStamp", CALENDAR.getTimeInMillis()/1000+(15*60));
        PutItemOutcome outcome = table.putItem(item);
    }

    public Item getItem(String email) {
        DynamoDB dynamoDB = new DynamoDB(DYNAMO_DB);
        Table table = dynamoDB.getTable("csye6225");
        return table.getItem("username", email);
    }

    public Object handleRequest(SNSEvent snsEvent, Context context) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime());
        context.getLogger().log("Invocation Started: "+timeStamp);
        if(snsEvent == null){
            context.getLogger().log("SNS Null Event");
        }else {
            context.getLogger().log("Number of Records: "+snsEvent.getRecords().size());
            String email = snsEvent.getRecords().get(0).getSNS().getMessage();
            context.getLogger().log("Record Message: "+email);
            timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime());
            context.getLogger().log("Invocation Completed: "+timeStamp);
            try{
                Item item = getItem(email);
                String token;
                if(item == null) {
                    putItem(email);
                    item = getItem(email);
                }
                token = (String)item.get(email);
                sendEmail(email, token);
                context.getLogger().log("Email sent!");
            }catch(Exception exc){
                context.getLogger().log("The email was not sent. Error message: "+exc.getMessage());
            }
        }
        return null;
    }
}