package com.sicame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SICAME Predictive Maintenance — Spring Boot entry point.
 *
 * Architecture:
 *   Angular 21 (4200)  →  This backend (8080)  →  Python ML (8765)
 *
 * On startup:
 *   1. H2 schema is created/updated via JPA
 *   2. DataLoaderService imports mh2.csv + Equipement.csv (idempotent)
 *   3. ML health check verifies Python service is reachable
 *   4. Daily retrain scheduler starts
 */
@SpringBootApplication
@EnableScheduling
public class SicameApplication {

    public static void main(String[] args) {
        SpringApplication.run(SicameApplication.class, args);
    }
}
