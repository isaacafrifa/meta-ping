package com.iam.event_cast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.UUID;
import java.util.function.Function;


@SpringBootApplication
public class MetaPingApplication {
	private static final Logger logger = LoggerFactory.getLogger(MetaPingApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(MetaPingApplication.class, args);
	}

	@Bean
	public Function<String, String> reverseString() {
		return value -> {
			// Generate a unique trace ID for this request
			String traceId = UUID.randomUUID().toString();

			try {
				// Add trace ID to MDC for request tracing
				MDC.put("traceId", traceId);

				logger.debug("Received request to reverse string: {}", value);
				// Handle null or empty input
				if (value == null || value.isEmpty()) {
					logger.warn("Empty or null input received");
					return "";
				}

				String result = new StringBuilder(value).reverse().toString();

				logger.debug("String reversed successfully: '{}' -> '{}'", value, result);
				return result;
			} catch (Exception e) {
				logger.error("Error reversing string: {}", value, e);
				throw e;
			} finally {
				// Always clear the MDC to prevent memory leaks
				MDC.clear();
			}
		};
	}
}
