package com.iam.metaping.integration.helpers;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Base class for integration tests requiring LocalStack.
 * Handles Docker availability checks and provides AWS client builders.
 */
public class AbstractLocalStackIT {


    protected static final boolean DOCKER_AVAILABLE;
    protected static final String LOCALSTACK_ACCOUNT_ID = "000000000000";

    static {
        boolean available;
        try {
            DockerClientFactory.instance().client();
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        DOCKER_AVAILABLE = available;
    }

    protected static SnsClient buildSnsClient(LocalStackContainer localstack) {
        return SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(SNS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
    }

    protected static SqsClient buildSqsClient(LocalStackContainer localstack) {
        return SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(SQS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
    }

    @BeforeAll
    static void checkDockerAvailability() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker is not available; skipping integration test");
    }
}
