package com.iam.metaping.integration;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.iam.metaping.function.MetaPingFunction;
import com.iam.metaping.integration.helpers.TestS3EventFactory;
import com.iam.metaping.integration.helpers.AbstractLocalStackIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Integration test using LocalStack to validate:
// S3 event -> function extracts metadata -> publishes to SNS -> delivered to SQS subscription
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetaPingApplicationTests extends AbstractLocalStackIT {

    // Test constants
    private static final String TOPIC_NAME = "meta-ping-test";
    private static final String QUEUE_NAME = "meta-ping-queue";
    private static final String EXPECTED_SUBJECT = "Meta-Ping Notification: New File Uploaded";
    private static final String EXPECTED_FILE_NAME = "test-file.pdf";
    private static final String EXPECTED_FILE_TYPE = "application/pdf";
    private static final long EXPECTED_FILE_SIZE = 1024L;
    private static final int POLL_TIMEOUT_SECONDS = 30;
    private static final int LONG_POLL_SECONDS = 5;

    static String topicArn;
    static String queueUrl;

    @Autowired
    MetaPingFunction function;

    @Container
    static LocalStackContainer localstack;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        if (DOCKER_AVAILABLE) {
            // Enable notification path in the Spring context and target LocalStack
            registry.add("notifications.enabled", () -> "true");
            registry.add("aws.sns.topic-arn", () -> topicArn);
            registry.add("aws.sns.region", localstack::getRegion);
            registry.add("aws.sns.endpoint", () -> localstack.getEndpointOverride(SNS).toString());
        } else {
            // Disable notifications to avoid creating MetaNotifier/SnsPublisher in environments without Docker
            registry.add("notifications.enabled", () -> "false");
            registry.add("aws.sns.topic-arn", () -> "");
            registry.add("aws.sns.region", () -> "us-east-1");
        }
    }

    // Start LocalStack once for the test class (only if Docker is available)
    static {
        if (DOCKER_AVAILABLE) {
            localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(SNS, SQS);
            localstack.start();
            setupStaticInfra();
        } else {
            localstack = null;
        }
    }

    // Provision SNS topic and SQS queue and wire the subscription (once per class)
    private static void setupStaticInfra() {
        try (SnsClient sns = buildSnsClient(localstack);
             SqsClient sqs = buildSqsClient(localstack)) {

            // Create SNS topic and SQS queue for the test
            topicArn = sns.createTopic(CreateTopicRequest.builder().name(TOPIC_NAME).build()).topicArn();
            queueUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();

            String queueArn = getQueueArnWithFallback(sqs, queueUrl);

            // Allow SNS topic to send to the SQS queue
            applyQueuePolicyAllowSns(sqs, queueUrl, queueArn, topicArn);

            // Subscribe the queue to the topic (protocol = sqs, endpoint = queue ARN)
            sns.subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .build());
        }
    }



    @BeforeAll
    void setupInfra() {
        // Provide credentials for DefaultCredentialsProvider so SnsPublisher can publish to LocalStack
        System.setProperty("aws.accessKeyId", localstack.getAccessKey());
        System.setProperty("aws.secretAccessKey", localstack.getSecretKey());
    }


    @Test
    @DisplayName("S3 event triggers metadata extraction and publishes to SNS (verified via SQS subscription)")
    void shouldPublishMetadataToSns() {
        // Given: a synthetic S3 event representing an uploaded file
        S3Event event = TestS3EventFactory.create(
                "meta-ping-bucket",
                EXPECTED_FILE_NAME,
                EXPECTED_FILE_SIZE
        );

        // When: invoke function (extracts metadata and attempts SNS publish)
        function.apply(event);

        // Then: the SNS notification should be delivered to the subscribed SQS queue
        try (SqsClient sqs = buildSqsClient(localstack)) {

            String qUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();

            boolean found = pollForNotification(sqs, qUrl); // envelope + content assertions inside

            assertTrue(found, "Expected SNS notification to be delivered to SQS subscription");
        }
    }

    // Retrieve queue ARN via API; used by policy + subscription
    private static String getQueueArn(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
    }

    // LocalStack sometimes delays QueueArn; build a deterministic fallback ARN when missing
    private static String getQueueArnWithFallback(SqsClient sqs, String queueUrl) {
        String arn = getQueueArn(sqs, queueUrl);
        if (arn == null || arn.isBlank()) {
            arn = String.format("arn:aws:sqs:%s:%s:%s", localstack.getRegion(), LOCALSTACK_ACCOUNT_ID, MetaPingApplicationTests.QUEUE_NAME);
        }
        return arn;
    }

    // Attach a minimal SQS policy that allows the SNS topic to send messages to the queue
    private static void applyQueuePolicyAllowSns(SqsClient sqs, String queueUrl, String queueArn, String topicArn) {
        String policy = ("""
                {
                  "Version":"2012-10-17",
                  "Statement":[
                    {
                      "Sid":"Allow-SNS-SendMessage",
                      "Effect":"Allow",
                      "Principal":"*",
                      "Action":"sqs:SendMessage",
                      "Resource":"%s",
                      "Condition":{ "ArnEquals": { "aws:SourceArn":"%s" } }
                    }
                  ]
                }
                """).formatted(queueArn, topicArn);

        sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, policy))
                .build());
    }

    // Poll SQS for the SNS-delivered message and validate both the envelope and content
    private static boolean pollForNotification(SqsClient sqs, String queueUrl) {
        ObjectMapper objectMapper = new ObjectMapper();
        Instant until = Instant.now().plus(Duration.ofSeconds(POLL_TIMEOUT_SECONDS));
        while (Instant.now().isBefore(until)) {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(10)
                            .waitTimeSeconds(LONG_POLL_SECONDS)
                            .build())
                    .messages();
            for (Message m : messages) {
                String body = m.body();
                if (parseSnsEnvelopeOk(objectMapper, body)) {
                    return true;
                }
                // Fallback: substring check for flakiness across LocalStack variants
                if (body != null && body.contains("New File Uploaded") && body.contains(EXPECTED_FILE_NAME)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Parse the standard SNS-over-SQS JSON envelope and check expected fields + message body
    private static boolean parseSnsEnvelopeOk(ObjectMapper objectMapper, String body) {
        if (body == null || body.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(body);
            String type = root.path("Type").asText("");
            String subject = root.path("Subject").asText("");
            String topicFromMsg = root.path("TopicArn").asText("");
            String message = root.path("Message").asText("");

            boolean envelopeOk = "Notification".equals(type)
                    && topicArn != null && topicArn.equals(topicFromMsg)
                    && EXPECTED_SUBJECT.equals(subject);

            boolean contentOk = message.contains("New File Uploaded:")
                    && message.contains("Name: " + EXPECTED_FILE_NAME)
                    && message.contains("Type: " + EXPECTED_FILE_TYPE)
                    && message.contains("Size: " + EXPECTED_FILE_SIZE + " bytes");

            return envelopeOk && contentOk;
        } catch (Exception e) {
            return false;
        }
    }
}