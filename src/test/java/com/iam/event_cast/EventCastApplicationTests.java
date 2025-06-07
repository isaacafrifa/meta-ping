package com.iam.event_cast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = EventCastApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventCastApplicationTests {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    @DisplayName("Running the test for the ReverseString function")
    void testReverseString() throws Exception {
        String input = "hello";
        String expected = "olleh";

        // Test using POST endpoint
        ResponseEntity<String> result = testRestTemplate.exchange(
                RequestEntity.post(new URI("/reverseString")).body(input), String.class);

        assertEquals(expected, result.getBody());
    }
}