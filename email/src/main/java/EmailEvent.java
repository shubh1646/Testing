import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.*;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EmailEvent implements RequestHandler<SNSEvent, Object> {
    static final AmazonDynamoDB DYNAMO_DB = AmazonDynamoDBClientBuilder.standard().build();
    static final AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(Regions.US_EAST_1).build();

    static final Calendar CALENDAR = Calendar.getInstance();

    private static final String DOMAIN = System.getenv("domain");
    private static final String TABLE = System.getenv("table");

    private static final String FROM = "admin@"+DOMAIN;
    private static final String SUBJECT = "Password Reset Link";
    private static final String BODY = "<h1>AWS Library Management System</h1>"+ "<h3>Actioned required</h3>"
            + "<p>You are receiving this email in response to your password reset request "
            + "for your AWS Library Management Account with LoginId: ";

    private void sendEmail(String email, String token) throws Exception{
        
        String content = BODY+email+"</p><p>link to reset password: "+
                "<a href='#'>http://"+DOMAIN+"/reset?email="+email+"&token="+token+"</a></p>";

        SendEmailRequest request = new SendEmailRequest().withDestination(new Destination().withToAddresses(email))
                .withMessage(new Message()
                        .withBody(new Body()
                                .withHtml(new Content().withCharset("UTF-8").withData(content))
                               .withText(new Content().withCharset("UTF-8").withData(content)))
                        .withSubject(new Content().withCharset("UTF-8").withData(SUBJECT)))
                .withSource(FROM);
        client.sendEmail(request);
    }

    private PutItemOutcome putItem(String email, Context context) {
        DynamoDB dynamoDB = new DynamoDB(DYNAMO_DB);
        Table table = dynamoDB.getTable(TABLE);
        long timeStamp = (CALENDAR.getTimeInMillis()/1000)+(1*60);
        context.getLogger().log("----------------------------set"+timeStamp);
        Item item = new Item()
                .withPrimaryKey("emailId", email)
                .withString("token", UUID.randomUUID().toString())
                .withNumber("timeStamp", timeStamp);
        PutItemOutcome outcome = table.putItem(item);
        return outcome;
    }

    private Item getItem(String email, Context context) {
        DynamoDB dynamoDB = new DynamoDB(DYNAMO_DB);
        Table table = dynamoDB.getTable(TABLE);
        Item item = table.getItem("emailId", email);
        if(item !=null){
            long timeStampVal = Long.parseLong(item.get("timeStamp").toString());
            long currentTime = (CALENDAR.getTimeInMillis()/1000);
            context.getLogger().log("----------------------------old:    "+timeStampVal);
            context.getLogger().log("----------------------------new:    "+currentTime);
            if(timeStampVal<currentTime) {
                context.getLogger().log("----------------------------old:    "+timeStampVal);
                context.getLogger().log("----------------------------new:    "+currentTime);
                return updateItem(email, table, context);
            }
        }
        return item;
    }

    private Item updateItem(String email, Table table, Context context) {
        Map<String, String> expressionAttributeNames = new HashMap<String, String>();
        expressionAttributeNames.put("#T", "token");

        Map<String, Object> expressionAttributeValues = new HashMap<String, Object>();
        expressionAttributeValues.put(":val1", UUID.randomUUID().toString());

        UpdateItemOutcome outcome =  table.updateItem("emailId", email, "set #T = #T - :val1", expressionAttributeNames,
                expressionAttributeValues);
        context.getLogger().log(outcome.getItem().toJSON());
        return outcome.getItem();
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
            try{
                Item item = getItem(email,context);
                String tokenVal;
                if(item == null) {
                    putItem(email,context);
                    item = getItem(email,context);
                    tokenVal = (String)item.get("token");
                    sendEmail(email, tokenVal);
                    context.getLogger().log("Email Sent!");
                }else {
                    context.getLogger().log("Email already Sent!");
                }
            }catch(Exception exc){
                exc.printStackTrace();
                context.getLogger().log("The email was not sent. Error message: "+exc.getMessage());
            }
        }
        timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime());
        context.getLogger().log("Invocation Completed: "+timeStamp);
        return null;
    }
}
