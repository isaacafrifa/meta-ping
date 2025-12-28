package com.iam.metaping.integration.helpers;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;

import java.util.List;

public class TestS3EventFactory {

    public static S3Event create(String bucket, String key, long size) {
        // Using the internal S3EventNotification models to build the hierarchy
        S3EventNotification.S3BucketEntity bucketEntity = new S3EventNotification.S3BucketEntity(bucket, null, null);
        S3EventNotification.S3ObjectEntity objectEntity = new S3EventNotification.S3ObjectEntity(key, size, null, null, null);
        S3EventNotification.S3Entity s3Entity = new S3EventNotification.S3Entity(null, bucketEntity, objectEntity, null);

        S3EventNotification.S3EventNotificationRecord eventRecord = new S3EventNotification.S3EventNotificationRecord(
                "us-east-1",
                "ObjectCreated:Put",
                "aws:s3",
                null,
                "2.1",
                null,
                null,
                s3Entity,
                null
        );

        return new S3Event(List.of(eventRecord));
    }
}
