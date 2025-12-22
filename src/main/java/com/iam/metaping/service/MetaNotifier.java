package com.iam.metaping.service;

import com.iam.metaping.model.FileMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class MetaNotifier {

    private final SnsPublisher snsPublisher;

    public MetaNotifier(SnsPublisher snsPublisher) {
        this.snsPublisher = snsPublisher;
    }

    public boolean notifyNewFile(FileMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        String message = ("""
                New File Uploaded:
                Name: %s
                Type: %s
                Size: %d bytes
                """
        ).formatted(
                metadata.fileName(),
                metadata.fileType(),
                metadata.fileSize()
        );

        return snsPublisher.publish("Meta-Ping Notification: New File Uploaded", message);
    }
}
