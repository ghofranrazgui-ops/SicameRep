package com.sicame.controller;

import com.sicame.service.ProductivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Productivity and energy metrics endpoints.
 *
 * GET /api/productivity/global                          — all machines, full 2005 year
 * GET /api/productivity/global?from=2005-01-01&to=...  — date range
 * GET /api/productivity/{machineCode}?from=...&to=...  — single machine
 * GET /api/productivity/today/global                   — today's snapshot
 * GET /api/productivity/today/{machineCode}            — today single machine
 */
@RestController
@RequestMapping("/api/productivity")
@RequiredArgsConstructor
public class ProductivityController {

    private final ProductivityService productivityService;

    @GetMapping("/global")
    public ResponseEntity<Map<String, Object>> getGlobal(
            @RequestParam(defaultValue = "2005-01-01")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "2005-12-31")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(productivityService.getGlobalProductivity(from, to));
    }

    @GetMapping("/{machineCode}")
    public ResponseEntity<Map<String, Object>> getMachine(
            @PathVariable String machineCode,
            @RequestParam(defaultValue = "2005-01-01")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "2005-12-31")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(
                productivityService.getMachineProductivity(machineCode, from, to));
    }

    @GetMapping("/today/global")
    public ResponseEntity<Map<String, Object>> getTodayGlobal() {
        LocalDate today = LocalDate.now();
        return ResponseEntity.ok(productivityService.getGlobalProductivity(today, today));
    }

    @GetMapping("/today/{machineCode}")
    public ResponseEntity<Map<String, Object>> getTodayMachine(
            @PathVariable String machineCode) {
        LocalDate today = LocalDate.now();
        return ResponseEntity.ok(
                productivityService.getMachineProductivity(machineCode, today, today));
    }
}
