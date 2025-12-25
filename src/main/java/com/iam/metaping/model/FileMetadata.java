package com.iam.metaping.model;

/**
 * Represents basic metadata about a file received from an S3 event.
 * <p>
 * This model captures the essential attributes that are typically present
 * in object-created notifications: the object key (file name), its size
 * in bytes, and a logical file type (e.g., content type or an inferred
 * type label).
 */
public record FileMetadata(
        String fileName,
        long fileSize,
        String fileType
) {
}
