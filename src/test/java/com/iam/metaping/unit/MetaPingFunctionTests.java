package com.iam.metaping.unit;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.iam.metaping.function.MetaPingFunction;
import com.iam.metaping.model.FileMetadata;
import com.iam.metaping.service.MetaNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MetaPingFunction} focusing on S3 event parsing, metadata extraction,
 * and optional notification publishing behavior.
 */
@ExtendWith(MockitoExtension.class)
class MetaPingFunctionTests {

    // Optional notifier via provider; will be configured per test
    @Mock
    private ObjectProvider<MetaNotifier> provider;

    @Mock
    private MetaNotifier notifier;

    @Test
    @DisplayName("Valid event: extracts decoded name, size and MIME type")
    void validEventProducesMetadata() {
        // Mock structure:
        // records[0].s3.object.key = "folder%2Ftest%20file.txt"
        // records[0].s3.object.size = 12345
        S3Event event = buildEvent("folder%2Ftest%20file.txt", 12345L, true);

        MetaPingFunction function = new MetaPingFunction(null);
        String result = function.apply(event);

        // Expect MIME to be text/plain for .txt
        String expected = "FileMetadata[fileName=folder/test file.txt, fileSize=12345, fileType=text/plain]";
        assertEquals(expected, result);
    }

    @Test
    @DisplayName("When notifier is enabled, it is invoked with extracted metadata")
    void notifierEnabledPublishes() {
        // Arrange
        when(provider.getIfAvailable()).thenReturn(notifier);

        MetaPingFunction fnWithNotifier = new MetaPingFunction(provider);

        // Build a valid event
        String key = "folder%2Ffile.pdf";
        long size = 2048L;
        S3Event event = buildEvent(key, size, true);

        // Expected metadata derived by the function
        FileMetadata expected = new FileMetadata("folder/file.pdf", size, "application/pdf");

        // Stub notifier to return true
        when(notifier.notifyNewFile(expected)).thenReturn(true);

        // Act
        String result = fnWithNotifier.apply(event);

        // Assert: output and interaction
        assertEquals(expected.toString(), result);
        verify(provider, times(1)).getIfAvailable();
        verify(notifier, times(1)).notifyNewFile(expected);
        verifyNoMoreInteractions(notifier);
    }

    @Test
    @DisplayName("When notifier is disabled/absent, function skips publish and still returns metadata")
    void notifierDisabledSkipsPublish() {
        // Arrange: provider returns null (no bean created when notifications.enabled=false)
        when(provider.getIfAvailable()).thenReturn(null);

        MetaPingFunction fnNoNotifier = new MetaPingFunction(provider);

        S3Event event = buildEvent("image%2Fphoto.jpg", 10_000L, true);
        String expected = "FileMetadata[fileName=image/photo.jpg, fileSize=10000, fileType=image/jpeg]";

        // Act
        String result = fnNoNotifier.apply(event);

        // Assert: result is still correct and provider was queried
        assertEquals(expected, result);
        verify(provider, times(1)).getIfAvailable();
        verifyNoMoreInteractions(provider);
    }

    @Test
    @DisplayName("Null event -> no-s3-records error")
    void nullEvent() {
        MetaPingFunction function = new MetaPingFunction(null);
        String result = function.apply(null);
        assertEquals("{\"error\":\"no-s3-records\"}", result);
    }

    @Test
    @DisplayName("Empty Records -> no-s3-records error")
    void emptyRecords() {
        S3Event event = mock(S3Event.class);
        when(event.getRecords()).thenReturn(Collections.emptyList());
        MetaPingFunction function = new MetaPingFunction(null);
        String result = function.apply(event);
        assertEquals("{\"error\":\"no-s3-records\"}", result);
    }

    @Test
    @DisplayName("Missing object in record -> invalid-s3-record error")
    void missingObject() {
        // Build event with S3 entity present but object missing
        S3EventNotification.S3EventNotificationRecord record = mock(S3EventNotification.S3EventNotificationRecord.class);
        S3EventNotification.S3Entity s3Entity = mock(S3EventNotification.S3Entity.class);
        when(record.getS3()).thenReturn(s3Entity);

        S3Event event = mock(S3Event.class);
        when(event.getRecords()).thenReturn(List.of(record));

        MetaPingFunction function = new MetaPingFunction(null);
        String result = function.apply(event);
        assertEquals("{\"error\":\"invalid-s3-record\"}", result);
    }

    @Test
    @DisplayName("Missing size -> fileSize defaults to -1")
    void missingSizeDefaultsToMinusOne() {
        // Build event with key only (no size)
        S3Event event = buildEvent("image%2Fphoto.png", null, true);
        MetaPingFunction function = new MetaPingFunction(null);
        String result = function.apply(event);

        // For .png, MIME is typically image/png
        String expected = "FileMetadata[fileName=image/photo.png, fileSize=-1, fileType=image/png]";
        assertEquals(expected, result);
    }

    // Helper to build an S3Event via mocks
    private S3Event buildEvent(String rawKey, Long size, boolean includeS3Entity) {
        S3EventNotification.S3EventNotificationRecord record = mock(S3EventNotification.S3EventNotificationRecord.class);
        if (includeS3Entity) {
            S3EventNotification.S3Entity s3 = mock(S3EventNotification.S3Entity.class);
            S3EventNotification.S3ObjectEntity obj = mock(S3EventNotification.S3ObjectEntity.class);
            when(obj.getKey()).thenReturn(rawKey);
            when(obj.getSizeAsLong()).thenReturn(size);
            when(s3.getObject()).thenReturn(obj);
            when(record.getS3()).thenReturn(s3);
        } else {
            when(record.getS3()).thenReturn(null);
        }

        S3Event event = mock(S3Event.class);
        when(event.getRecords()).thenReturn(List.of(record));
        return event;
    }
}
