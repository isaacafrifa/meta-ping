package com.iam.metaping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = MetaPingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetaPingApplicationTests {

    @Test
    @DisplayName("Running the dummy test")
    void test() {
        String expected = "";
        String actual = "";

        assertEquals(expected, actual);
    }
}