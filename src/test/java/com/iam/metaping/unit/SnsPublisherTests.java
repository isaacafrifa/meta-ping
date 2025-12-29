package com.iam.metaping.unit;

import com.iam.metaping.config.SnsProperties;
import com.iam.metaping.service.SnsPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Running unit tests for SnsPublisher")
class SnsPublisherTests {

    private SnsProperties configuredProps() {
        SnsProperties props = new SnsProperties();
        props.setTopicArn("arn:aws:sns:eu-west-1:123456789012:test-topic");
        props.setRegion("eu-west-1");
        return props;
    }

    @Test
    @DisplayName("publish: configured client, with subject -> sends request and returns true")
    void publishWithSubjectSuccess() {
        // Given
        SnsPublisher publisher = new SnsPublisher(configuredProps());
        SnsClient sns = mock(SnsClient.class);
        ReflectionTestUtils.setField(publisher, "snsClient", sns);

        when(sns.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId("mid-1").build());

        // When
        boolean ok = publisher.publish("Hello", "World message");

        // Then
        assertTrue(ok);
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns, times(1)).publish(captor.capture());
        PublishRequest req = captor.getValue();
        assertEquals("arn:aws:sns:eu-west-1:123456789012:test-topic", req.topicArn());
        assertEquals("World message", req.message());
        assertEquals("Hello", req.subject());
    }

    @Test
    @DisplayName("publish: configured client, null subject -> subject omitted")
    void publishWithoutSubject() {
        // Given
        SnsPublisher publisher = new SnsPublisher(configuredProps());
        SnsClient sns = mock(SnsClient.class);
        ReflectionTestUtils.setField(publisher, "snsClient", sns);
        when(sns.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId("mid-2").build());

        // When
        boolean ok = publisher.publish(null, "Body");

        // Then
        assertTrue(ok);
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(captor.capture());
        PublishRequest req = captor.getValue();
        assertEquals("Body", req.message());
        assertNull(req.subject(), "Subject must be null when not provided");
    }

    @Test
    @DisplayName("publish: blank message -> returns false and does not call SNS")
    void blankMessageShortCircuits() {
        // Given
        SnsPublisher publisher = new SnsPublisher(configuredProps());
        SnsClient sns = mock(SnsClient.class);
        ReflectionTestUtils.setField(publisher, "snsClient", sns);

        // When
        assertFalse(publisher.publish("S", " \t\n"));

        // Then
        verify(sns, never()).publish(any(PublishRequest.class));
    }

    @Test
    @DisplayName("publish: not configured (no topic/region) -> returns false")
    void notConfiguredReturnsFalse() {
        // Given
        SnsProperties props = new SnsProperties();
        // leave topicArn and region empty
        SnsPublisher publisher = new SnsPublisher(props);

        // When
        assertFalse(publisher.publish("S", "Message"));
    }

    @Test
    @DisplayName("publishJson: delegates to publish with same arguments")
    void publishJsonDelegates() {
        // Given
        // Use not-configured to avoid real client build side-effects
        SnsPublisher spyPublisher = spy(new SnsPublisher(new SnsProperties()));

        String subject = "Sub";
        String payload = "{\"k\":1}";

        // When
        // We don't stub publish; it will return false due to not-configured, which is fine.
        boolean result = spyPublisher.publishJson(subject, payload);

        // Then
        assertFalse(result);
        verify(spyPublisher, times(1)).publish(subject, payload);
    }
}
