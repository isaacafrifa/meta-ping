package com.iam.metaping;

import org.springframework.cloud.function.adapter.aws.FunctionInvoker;

/**
 * AWS Lambda handler backed by Spring Cloud Function's AWS adapter.
 * Summary: lets the framework boot Spring once per container and route invocations
 * to the function defined by the property `spring.cloud.function.definition` (e.g.,
 * "metaPingFunction") — keeping the handler minimal, config-driven, and warm-start friendly.
 */
public class MetaPingLambdaHandler extends FunctionInvoker { }
