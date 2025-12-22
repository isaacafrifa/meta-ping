package com.iam.metaping.function;

import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.iam.metaping.model.FileMetadata;
import com.iam.metaping.service.MetaNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Processes AWS S3 ObjectCreated events and extracts basic file metadata.
 * <p>
 * Input: {@link S3Event} (from AWS Lambda trigger)
 * Output: {@link String} representation of {@link FileMetadata}
 */
@Component
public class MetaPingFunction implements Function<S3Event, String> {

    private static final Logger LOG = LoggerFactory.getLogger(MetaPingFunction.class);
    private final ObjectProvider<MetaNotifier> metaNotifierProvider; // Optional provider; MetaNotifier bean exists only when notifications.enabled=true

    public MetaPingFunction(ObjectProvider<MetaNotifier> metaNotifierProvider) {
        this.metaNotifierProvider = metaNotifierProvider;
    }

    @Override
    public String apply(S3Event s3Event) {
        
        // Ensure the event contains at least one valid S3 record
        S3EventNotification.S3EventNotificationRecord s3EventRecord = validateS3Event(s3Event);

        if (s3EventRecord == null) {
            LOG.warn("No valid S3 record found in event");
            return "{\"error\":\"no-s3-records\"}";
        }

        S3EventNotification.S3ObjectEntity s3Object = s3EventRecord.getS3().getObject();
        if (s3Object == null) {
            LOG.warn("S3 record is missing S3 object entity");
            return "{\"error\":\"invalid-s3-record\"}";
        }

        // Build metadata from the S3 object (key, size, inferred type)
        FileMetadata metadata = extractFileMetadata(s3Object);
        LOG.info("Extracted file metadata: name='{}', size={}, type='{}'",
                metadata.fileName(), metadata.fileSize(), metadata.fileType());

        // Attempt notification publish if MetaNotifier bean is available via provider
        MetaNotifier notifier = metaNotifierProvider != null ? metaNotifierProvider.getIfAvailable() : null;
        if (notifier != null) {
            boolean published = notifier.notifyNewFile(metadata);
            LOG.info("MetaNotifier publish attempted. success={}", published);
        } else {
            LOG.debug("MetaNotifier bean not available; skipping notification publish.");
        }
  
        // Return string form; can be swapped for JSON serialization later
        return metadata.toString();
    }



    /**
     * Returns the first S3 record from the event if present and structurally valid.
     * S3 ObjectCreated events typically contain a single record; we only process one file at a time.
     * Returns {@code null} when the event or its record is missing/invalid.
     */
    private S3EventNotification.S3EventNotificationRecord validateS3Event(S3Event s3Event) {
        if (s3Event == null || s3Event.getRecords() == null || s3Event.getRecords().isEmpty()) {
            return null;
        }

        S3EventNotification.S3EventNotificationRecord firstRecord = s3Event.getRecords().get(0);
        if (firstRecord == null) {
            return null;
        }

        S3EventNotification.S3Entity s3Entity = firstRecord.getS3();
        if (s3Entity == null) {
            return null;
        }

        logS3RecordDetails(s3Entity);
        return firstRecord;
    }


    /**
     * Extracts {@link FileMetadata} from the provided S3 object entity.
     * - fileName: URL-decoded object key
     * - fileSize: size in bytes (or -1 when not present)
     * - fileType: MIME type inferred from name, defaulting to application/octet-stream
     */
    private FileMetadata extractFileMetadata(S3EventNotification.S3ObjectEntity s3Object) {
        String fileName = decodeFileName(s3Object.getKey());
        long fileSize = s3Object.getSizeAsLong() != null ? s3Object.getSizeAsLong() : -1L;
        String fileType = inferFileType(fileName);

        return new FileMetadata(fileName, fileSize, fileType);
    }

    /**
     * Decodes the S3 object key to a human-readable file name.
     * Replaces '+' with space prior to UTF-8 URL decoding to match S3 URL encoding behavior.
     */
    private String decodeFileName(String rawKey) {
        if (rawKey == null) {
            return "";
        }
        // URL-decode to handle spaces and special characters
        return URLDecoder.decode(rawKey.replace('+', ' '), StandardCharsets.UTF_8);
    }

    /**
     * Guesses a MIME type from the file name. Falls back to application/octet-stream when unknown.
     */
    private String inferFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "application/octet-stream";
        }
        // Guess based on file extension
        String inferredType = URLConnection.guessContentTypeFromName(fileName);
        return inferredType != null ? inferredType : "application/octet-stream";
    }

    /**
     * Logs the object key from the S3 entity for tracking purposes.
     */
    private void logS3RecordDetails(S3EventNotification.S3Entity s3Entity) {
        String rawKey = (s3Entity.getObject() != null) ? s3Entity.getObject().getKey() : "";
        LOG.info("Processing S3 record: key={}", rawKey);
    }

}
