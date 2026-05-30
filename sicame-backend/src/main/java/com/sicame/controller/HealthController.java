package com.sicame.controller;

import com.sicame.repository.EquipmentRepository;
import com.sicame.repository.MaintenanceEventRepository;
import com.sicame.service.MlClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health — system health check.
 * Returns status of the backend, database row counts, and ML service reachability.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final MlClientService            mlClient;
    private final MaintenanceEventRepository eventRepo;
    private final EquipmentRepository        equipmentRepo;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",         "UP");
        resp.put("service",        "SICAME Predictive Maintenance");
        resp.put("timestamp",      LocalDateTime.now().toString());
        resp.put("totalEvents",    eventRepo.count());
        resp.put("dashboardEvents",eventRepo.countBySource("dashboard"));
        resp.put("totalEquipment", equipmentRepo.count());
        resp.put("mlService",      mlClient.getHealth());
        return ResponseEntity.ok(resp);
    }
}
