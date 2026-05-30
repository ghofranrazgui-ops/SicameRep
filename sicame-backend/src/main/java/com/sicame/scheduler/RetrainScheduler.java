package com.sicame.scheduler;

import com.sicame.service.MlClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Scheduled jobs for the ML service integration.
 *
 * 1. dailyRetrain  — fires at 02:00 every night, triggers Python retraining.
 * 2. mlHealthCheck — fires every 10 minutes, logs warnings if Python is unreachable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrainScheduler {

    private final MlClientService mlClient;

    /** Daily ML retrain — cron configurable via application.properties */
    @Scheduled(cron = "${sicame.scheduler.retrain-cron:0 0 2 * * *}")
    public void dailyRetrain() {
        log.info("[SCHEDULER] Daily ML retrain triggered at {}", LocalDateTime.now());
        try {
            Map<String, Object> result = mlClient.triggerRetrain();
            log.info("[SCHEDULER] Retrain complete — status={}, c_index={}, elapsed_s={}",
                    result.get("status"),
                    result.get("cox_c_index"),
                    result.get("elapsed_s"));
        } catch (Exception e) {
            log.error("[SCHEDULER] Daily retrain failed: {}", e.getMessage());
        }
    }

    /** ML service health check every 10 minutes */
    @Scheduled(fixedDelayString = "${sicame.scheduler.health-interval-ms:600000}")
    public void mlHealthCheck() {
        boolean alive = mlClient.isAlive();
        if (!alive) {
            log.warn("[SCHEDULER] Python ML service is unreachable at {} — predictions may be stale",
                    LocalDateTime.now());
        } else {
            log.debug("[SCHEDULER] ML service healthy");
        }
    }
}
