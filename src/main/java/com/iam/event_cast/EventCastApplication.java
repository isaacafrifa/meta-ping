package com.iam.event_cast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.function.Function;


@SpringBootApplication
public class EventCastApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventCastApplication.class, args);
	}

@Bean
	public Function<String, String> reverseString() {
		return value -> new StringBuilder(value).reverse().toString();
	}
}
