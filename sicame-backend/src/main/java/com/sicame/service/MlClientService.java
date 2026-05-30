package com.sicame.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * HTTP client for the Python ML REST service (port 8765).
 *
 * Python returns snake_case keys (machine_code, risk_tier, hazard_score…).
 * This service converts them to camelCase before sending to Angular.
 */
@Slf4j
@Service
public class MlClientService {

    private final RestTemplate restTemplate;

    @Value("${sicame.ml.base-url:http://localhost:8765}")
    private String mlBaseUrl;

    public MlClientService(@Qualifier("mlRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ── Predictions ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAllPredictions() {
        log.debug("Fetching all ML predictions");
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    mlBaseUrl + "/ml/predict", Map.class);
            if (resp.getBody() == null) return Map.of("predictions", Map.of());

            Map<String, Object> body = resp.getBody();

            // Convert predictions map: each machine entry snake_case → camelCase
            Object rawPredictions = body.get("predictions");
            if (rawPredictions instanceof Map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                ((Map<String, Object>) rawPredictions).forEach((code, val) -> {
                    if (val instanceof Map) {
                        converted.put(code, convertMachinePrediction((Map<String, Object>) val));
                    }
                });
                body.put("predictions", converted);
            }

            // Convert model_summary → modelSummary
            Object rawSummary = body.get("model_summary");
            if (rawSummary instanceof Map) {
                body.put("modelSummary", toCamelCase((Map<String, Object>) rawSummary));
                body.remove("model_summary");
            }

            // generated_at → generatedAt, model_version → modelVersion
            remapKey(body, "generated_at", "generatedAt");
            remapKey(body, "model_version", "modelVersion");

            return body;
        } catch (Exception e) {
            log.error("getAllPredictions failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "predictions", Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPrediction(String machineCode) {
        log.debug("Fetching ML prediction for machine {}", machineCode);
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    mlBaseUrl + "/ml/predict/" + machineCode, Map.class);
            if (resp.getBody() == null) return Map.of();
            return convertMachinePrediction(resp.getBody());
        } catch (Exception e) {
            log.error("getPrediction({}) failed: {}", machineCode, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    // ── Training ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerRetrain() {
        log.info("Triggering ML retrain");
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    mlBaseUrl + "/ml/train", null, Map.class);
            return resp.getBody() != null ? toCamelCase(resp.getBody()) : Map.of("status", "ok");
        } catch (Exception e) {
            log.error("triggerRetrain failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "status", "unavailable");
        }
    }

    // ── Log Fix + Retrain ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> logFixAndRetrain(String machineCode, String eventDate,
                                                String interventionType, double durationH,
                                                String notes) {
        log.info("Logging fix to Python ML: machine={} type={} date={} dur={}h",
                machineCode, interventionType, eventDate, durationH);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("machine_code",      machineCode);
            body.put("event_date",        eventDate);
            body.put("intervention_type", interventionType);
            body.put("duration_h",        durationH);
            body.put("notes",             notes != null ? notes : "");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    mlBaseUrl + "/ml/log-fix", request, Map.class);
            return resp.getBody() != null ? toCamelCase(resp.getBody()) : Map.of();
        } catch (Exception e) {
            log.error("logFixAndRetrain failed: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    // ── Metadata ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getHealth() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    mlBaseUrl + "/ml/health", Map.class);
            return resp.getBody() != null ? toCamelCase(resp.getBody()) : Map.of("status", "unknown");
        } catch (Exception e) {
            return Map.of("status", "unavailable", "error", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getFeatureImportance() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    mlBaseUrl + "/ml/feature-importance", Map.class);
            return resp.getBody() != null ? resp.getBody() : Map.of("features", List.of());
        } catch (Exception e) {
            log.error("getFeatureImportance failed: {}", e.getMessage());
            return Map.of("features", List.of());
        }
    }


    // ── Dual pipeline ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAllDualPredictions() {
        log.debug("Fetching dual ML predictions");
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    mlBaseUrl + "/ml/predict-dual", Map.class);
            if (resp.getBody() == null) return Map.of("predictions", Map.of());
            Map<String, Object> body = resp.getBody();
            Object rawPredictions = body.get("predictions");
            if (rawPredictions instanceof Map) {
                Map<String, Object> converted = new java.util.LinkedHashMap<>();
                ((Map<String, Object>) rawPredictions).forEach((code, val) -> {
                    if (val instanceof Map) {
                        converted.put(code, convertDualPrediction((Map<String, Object>) val));
                    }
                });
                body.put("predictions", converted);
            }
            remapKey(body, "generated_at",  "generatedAt");
            remapKey(body, "model_version",  "modelVersion");
            remapKey(body, "model_summary",  "modelSummary");
            remapKey(body, "classical_ml",   "classicalMl");
            return body;
        } catch (Exception e) {
            log.error("getAllDualPredictions failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "predictions", Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDualPrediction(String machineCode) {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    mlBaseUrl + "/ml/predict-dual/" + machineCode, Map.class);
            return resp.getBody() != null
                    ? convertDualPrediction(resp.getBody())
                    : Map.of("error", "no response");
        } catch (Exception e) {
            log.error("getDualPrediction({}) failed: {}", machineCode, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertDualPrediction(Map<String, Object> src) {
        // First apply standard Pipeline 1 camelCase conversion
        Map<String, Object> out = convertMachinePrediction(src);
        // Then convert nested pipeline2 object
        Object p2raw = src.get("pipeline2");
        if (p2raw instanceof Map) {
            Map<String, Object> p2 = (Map<String, Object>) p2raw;
            Map<String, Object> p2out = new java.util.LinkedHashMap<>();
            p2out.put("daysToFailure",       p2.getOrDefault("days_to_failure",       p2.get("daysToFailure")));
            p2out.put("breakdownIn30d",      p2.getOrDefault("breakdown_in_30d",      p2.get("breakdownIn30d")));
            p2out.put("breakdownIn14d",      p2.getOrDefault("breakdown_in_14d",      p2.get("breakdownIn14d")));
            p2out.put("bestRegressorName",   p2.getOrDefault("best_regressor_name",   p2.get("bestRegressorName")));
            p2out.put("bestRegressorMae",    p2.getOrDefault("best_regressor_mae",    p2.get("bestRegressorMae")));
            p2out.put("bestClassifierName",  p2.getOrDefault("best_classifier_name",  p2.get("bestClassifierName")));
            p2out.put("bestClassifierAuc",   p2.getOrDefault("best_classifier_auc",   p2.get("bestClassifierAuc")));
            p2out.put("timeSavedH",          p2.getOrDefault("time_saved_h",          p2.get("timeSavedH")));
            p2out.put("avgCurativeH",        p2.getOrDefault("avg_curative_h",        p2.get("avgCurativeH")));
            p2out.put("avgPreventiveH",      p2.getOrDefault("avg_preventive_h",      p2.get("avgPreventiveH")));
            if (p2.containsKey("error"))     p2out.put("error", p2.get("error"));
            out.put("pipeline2", p2out);
        }
        return out;
    }

    public boolean isAlive() {
        try {
            Map<String, Object> h = getHealth();
            return "ok".equals(h.get("status"));
        } catch (Exception e) {
            return false;
        }
    }

    // ── Key conversion helpers ────────────────────────────────

    /**
     * Converts a single machine prediction map from snake_case to camelCase.
     * Maps every field that Python outputs to its Angular-expected camelCase name.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertMachinePrediction(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Identity
        out.put("machineCode",    src.getOrDefault("machine_code",   src.get("machineCode")));
        out.put("machineName",    src.getOrDefault("machine_name",   src.get("machineName")));
        out.put("category",       src.get("category"));
        out.put("powerKw",        src.getOrDefault("power_kw",       src.get("powerKw")));

        // Status
        out.put("currentStatus",  src.getOrDefault("current_status",  src.get("currentStatus")));
        out.put("isCurrentlyDown",src.getOrDefault("is_currently_down",src.get("isCurrentlyDown")));

        // Survival
        out.put("survival7d",     src.getOrDefault("survival_7d",    src.get("survival7d")));
        out.put("survival14d",    src.getOrDefault("survival_14d",   src.get("survival14d")));
        out.put("survival30d",    src.getOrDefault("survival_30d",   src.get("survival30d")));

        // Risk
        out.put("riskTier",       src.getOrDefault("risk_tier",      src.get("riskTier")));
        out.put("riskProbabilities", src.getOrDefault("risk_probabilities", src.get("riskProbabilities")));
        out.put("hazardScore",    src.getOrDefault("hazard_score",   src.get("hazardScore")));
        out.put("reliabilityIndex",src.getOrDefault("reliability_index",src.get("reliabilityIndex")));

        // Time estimates
        out.put("daysSinceLastBreakdown",  src.getOrDefault("days_since_last_breakdown",  src.get("daysSinceLastBreakdown")));
        out.put("daysSinceLastPreventive", src.getOrDefault("days_since_last_preventive", src.get("daysSinceLastPreventive")));
        out.put("daysUntilPredictedNext",  src.getOrDefault("days_until_predicted_next",  src.get("daysUntilPredictedNext")));

        // History
        out.put("cumBreakdowns",  src.getOrDefault("cum_breakdowns", src.get("cumBreakdowns")));
        out.put("cumPreventive",  src.getOrDefault("cum_preventive", src.get("cumPreventive")));
        out.put("totalDowntimeH", src.getOrDefault("total_downtime_h",src.get("totalDowntimeH")));

        // Model metadata
        out.put("modelConfidence",src.getOrDefault("model_confidence",src.get("modelConfidence")));
        out.put("predictedAt",    src.getOrDefault("predicted_at",   src.get("predictedAt")));
        out.put("modelVersion",   src.getOrDefault("model_version",  src.get("modelVersion")));

        return out;
    }

    /**
     * Generic snake_case → camelCase converter for flat maps.
     */
    private Map<String, Object> toCamelCase(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(snakeToCamel(k), v));
        return out;
    }

    private String snakeToCamel(String snake) {
        if (!snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { nextUpper = true; }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    private void remapKey(Map<String, Object> map, String oldKey, String newKey) {
        if (map.containsKey(oldKey)) {
            map.put(newKey, map.remove(oldKey));
        }
    }
}
