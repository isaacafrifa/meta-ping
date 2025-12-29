package com.iam.metaping.service;

import com.iam.metaping.config.SnsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import java.net.URI;

/**
 * Simple SNS publisher backed by AWS SDK v2.
 * Configuration (application.properties):
 * - aws.sns.topic-arn=<your-topic-arn>
 * - aws.sns.region=<aws-region>
 * Notes:
 * - Values default from environment placeholders: {@code AWS_SNS_TOPIC_ARN} and
 *   {@code AWS_SNS_REGION} (falling back to {@code AWS_REGION}).
 * - Publishing is disabled when configuration is not set to real values; in that
 *   case {@code snsClient} remains {@code null} and calls safely no-op.
 */
@Component
public class SnsPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SnsPublisher.class);

    private final String topicArn;
    private final String region;
    private final SnsClient snsClient; // built once when configured; null means publishing is disabled
    private final String endpoint; // optional endpoint override

    public SnsPublisher(SnsProperties snsProperties) {
        String pTopicArn = snsProperties != null ? snsProperties.getTopicArn() : null;
        String pRegion = snsProperties != null ? snsProperties.getRegion() : null;
        String pEndpoint = snsProperties != null ? snsProperties.getEndpoint() : null;

        this.topicArn = pTopicArn == null ? "" : pTopicArn.trim();
        this.region = pRegion == null ? "" : pRegion.trim();
        this.endpoint = pEndpoint == null ? "" : pEndpoint.trim();

        // Build once if values look valid; otherwise leave snsClient null (disabled sentinel).
        if (isConfigured()) {
            SnsClientBuilder builder = SnsClient.builder()
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .region(Region.of(this.region));

            // Support LocalStack and integration testing by overriding the default AWS service endpoint.
            // Malformed URIs are caught to prevent application startup failure.
            if (!this.endpoint.isBlank()) {
                try {
                    builder = builder.endpointOverride(URI.create(this.endpoint));
                    LOG.info("SNS endpoint override active: {}", this.endpoint);
                } catch (Exception e) {
                    LOG.warn("Invalid aws.sns.endpoint '{}', ignoring.", this.endpoint);
                }
            }

            this.snsClient = builder.build();
        } else {
            this.snsClient = null;
        }
    }

    /**
     * Publish a message to the configured SNS topic.
     *
     * @param subject optional subject (displayed for some protocols like email)
     * @param message the message body
     * @return true if the publish call succeeded; false if publishing is disabled (no client)
     *         or the message is blank, or if an error occurs
     */
    public boolean publish(String subject, String message) {
        if (snsClient == null) {
            LOG.warn("SNS publisher not configured. Provide aws.sns.topic-arn and aws.sns.region to enable publishing.");
            return false;
        }

        if (message == null || message.isBlank()) {
            LOG.warn("SNS publish skipped: message is blank");
            return false;
        }

        try {
            PublishRequest.Builder request = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(message);

            if (subject != null && !subject.isBlank()) {
                request.subject(subject);
            }

            PublishResponse response = snsClient.publish(request.build());
            LOG.info("Published SNS message. messageId={}", response.messageId());
            return true;
        } catch (Exception e) {
            LOG.error("Failed to publish SNS message", e);
            return false;
        }
    }

    /**
     * Convenience method to publish a JSON payload using the provided serializer.
     * The caller is responsible for serializing the payload, keeping this class lightweight.
     */
    public boolean publishJson(String subject, String jsonPayload) {
        return publish(subject, jsonPayload);
    }

    private boolean isConfigured() {
        if (topicArn.isBlank() || region.isBlank()) {
            return false;
        }
        return true;
    }

}
