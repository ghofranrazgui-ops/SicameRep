package com.sicame.controller;

import com.sicame.service.MlClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ML endpoints — proxies to Python ML service.
 *
 * GET  /api/ml/predictions          — Pipeline 1 (Cox PH) all machines
 * GET  /api/ml/predictions/{code}   — Pipeline 1 single machine
 * GET  /api/ml/dual-predictions     — Pipeline 1 + 2 all machines
 * GET  /api/ml/dual-predictions/{code} — dual single machine
 * POST /api/ml/train                — trigger retrain
 * GET  /api/ml/health               — ML liveness
 * GET  /api/ml/feature-importance   — feature weights
 */
@Slf4j
@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
public class MlController {

    private final MlClientService mlClient;

    /* ── Pipeline 1 (Cox PH) ─────────────────────────────── */
    @GetMapping("/predictions")
    public ResponseEntity<Map<String,Object>> getAllPredictions() {
        return ResponseEntity.ok(mlClient.getAllPredictions());
    }

    @GetMapping("/predictions/{machineCode}")
    public ResponseEntity<Map<String,Object>> getPrediction(@PathVariable String machineCode) {
        return ResponseEntity.ok(mlClient.getPrediction(machineCode));
    }

    /* ── Dual pipeline ───────────────────────────────────── */
    @GetMapping("/dual-predictions")
    public ResponseEntity<Map<String,Object>> getAllDual() {
        return ResponseEntity.ok(mlClient.getAllDualPredictions());
    }

    @GetMapping("/dual-predictions/{machineCode}")
    public ResponseEntity<Map<String,Object>> getDual(@PathVariable String machineCode) {
        return ResponseEntity.ok(mlClient.getDualPrediction(machineCode));
    }

    /* ── Control ─────────────────────────────────────────── */
    @PostMapping("/train")
    public ResponseEntity<Map<String,Object>> triggerRetrain() {
        log.info("Manual ML retrain requested via API");
        return ResponseEntity.ok(mlClient.triggerRetrain());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String,Object>> mlHealth() {
        return ResponseEntity.ok(mlClient.getHealth());
    }

    @GetMapping("/feature-importance")
    public ResponseEntity<Map<String,Object>> featureImportance() {
        return ResponseEntity.ok(mlClient.getFeatureImportance());
    }
}
