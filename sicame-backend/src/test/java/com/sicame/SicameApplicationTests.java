package com.sicame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "sicame.data.mh-csv=src/test/resources/mh2_test.csv",
    "sicame.data.eq-csv=src/test/resources/Equipement_test.csv",
    "sicame.ml.base-url=http://localhost:9999",  // won't be called in tests
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"
})
class SicameApplicationTests {

    @Test
    void contextLoads() {
        // Spring context loads without errors
    }
}
