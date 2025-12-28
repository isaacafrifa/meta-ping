package com.iam.metaping.integration;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.iam.metaping.function.MetaPingFunction;
import com.iam.metaping.integration.helpers.TestS3EventFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Integration test variant with notifications disabled: the function should still
 * parse metadata but must not publish anything (queue remains empty).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetaPingNotificationsDisabledIT {

    private static final boolean DOCKER_AVAILABLE;

    private static final String TOPIC_NAME = "meta-ping-disabled-test";
    private static final String QUEUE_NAME = "meta-ping-disabled-queue";

    private static final String EXPECTED_FILE_NAME = "test-file.pdf";
    private static final String EXPECTED_FILE_TYPE = "application/pdf";
    private static final long EXPECTED_FILE_SIZE = 1024L;

    private static final int POLL_TIMEOUT_SECONDS = 10; // shorter since we expect no messages
    private static final int LONG_POLL_SECONDS = 2;

    static String queueUrl;

    @Autowired
    MetaPingFunction function;

    @Container
    static LocalStackContainer localstack;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        // Disable notifications so MetaNotifier bean is not created
        registry.add("notifications.enabled", () -> "false");
        // Provide default region to satisfy configuration consumers
        registry.add("aws.sns.region", () -> DOCKER_AVAILABLE ? localstack.getRegion() : "us-east-1");
        // Intentionally leave topic-arn empty; no publishing should occur
        registry.add("aws.sns.topic-arn", () -> "");
    }

    static {
        boolean available;
        try {
            DockerClientFactory.instance().client();
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        DOCKER_AVAILABLE = available;

        if (DOCKER_AVAILABLE) {
            localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(SNS, SQS);
            localstack.start();
            setupStaticInfra();
        } else {
            localstack = null;
        }
    }

    private static void setupStaticInfra() {
        AwsBasicCredentials creds = AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey());
        Region region = Region.of(localstack.getRegion());

        try (SnsClient sns = SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(SNS))
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build();
             SqsClient sqs = SqsClient.builder()
                     .endpointOverride(localstack.getEndpointOverride(SQS))
                     .region(region)
                     .credentialsProvider(StaticCredentialsProvider.create(creds))
                     .build()) {

            // Create a topic and a queue. We DO subscribe the queue to topic so that
            // if anything were published by mistake, it would be observable in SQS.
            String topicArn = sns.createTopic(CreateTopicRequest.builder().name(TOPIC_NAME).build()).topicArn();
            queueUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();

            String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()).attributes().get(QueueAttributeName.QUEUE_ARN);

            // Allow SNS topic to send to the SQS queue
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

            // Subscribe the queue to the topic
            sns.subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .build());
        }
    }

    @BeforeAll
    void ensureDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker is not available; skipping LocalStack integration test");
    }

    @Test
    @DisplayName("When notifications are disabled, function still returns metadata but no message reaches SQS")
    void shouldReturnMetadataWhenNotificationsDisabled() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker is not available; skipping LocalStack integration test");

        // Given: a valid S3 event
        S3Event event = TestS3EventFactory.create(
                "meta-ping-bucket",
                EXPECTED_FILE_NAME,
                EXPECTED_FILE_SIZE
        );

        // When: function is invoked (metadata extraction should still happen)
        String result = function.apply(event);

        // Then: returned string contains extracted metadata
        assertTrue(result.contains(EXPECTED_FILE_NAME));
        assertTrue(result.contains(Long.toString(EXPECTED_FILE_SIZE)));
        assertTrue(result.contains(EXPECTED_FILE_TYPE));

        // And: no messages should be present in SQS
        AwsBasicCredentials creds = AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey());
        Region region = Region.of(localstack.getRegion());

        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(SQS))
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build()) {

            String qUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();
            boolean anyMessage = pollAnyMessage(sqs, qUrl);
            assertFalse(anyMessage, "No messages should arrive when notifications are disabled");
        }
    }

    private static boolean pollAnyMessage(SqsClient sqs, String queueUrl) {
        Instant until = Instant.now().plus(Duration.ofSeconds(POLL_TIMEOUT_SECONDS));
        while (Instant.now().isBefore(until)) {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(10)
                            .waitTimeSeconds(LONG_POLL_SECONDS)
                            .build())
                    .messages();
            if (!messages.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
