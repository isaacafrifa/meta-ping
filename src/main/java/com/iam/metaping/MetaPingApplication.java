package com.iam.metaping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


@SpringBootApplication
@ConfigurationPropertiesScan
public class MetaPingApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetaPingApplication.class, args);
    }
}
