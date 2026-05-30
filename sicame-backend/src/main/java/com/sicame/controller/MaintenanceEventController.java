package com.sicame.controller;

import com.sicame.dto.ApiResponse;
import com.sicame.dto.LogFixRequest;
import com.sicame.model.MaintenanceEvent;
import com.sicame.repository.MaintenanceEventRepository;
import com.sicame.service.MlClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Maintenance event CRUD + the key log-fix endpoint.
 *
 * GET  /api/events                             — all events (optional ?machineCode=)
 * GET  /api/events/range?from=...&to=...       — date range filter
 * GET  /api/events/{machineCode}/breakdowns    — corrective events for one machine
 * POST /api/events/log-fix                     — log new intervention + retrain ML
 * DELETE /api/events/{id}                      — remove an event
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class MaintenanceEventController {

    private final MaintenanceEventRepository eventRepo;
    private final MlClientService            mlClient;

    @GetMapping
    public ResponseEntity<List<MaintenanceEvent>> getAll(
            @RequestParam(required = false) String machineCode,
            @RequestParam(required = false) String type) {

        if (machineCode != null) {
            return ResponseEntity.ok(
                    eventRepo.findByMachineCodeOrderByEventDateDesc(machineCode));
        }
        if (type != null) {
            return ResponseEntity.ok(
                    eventRepo.findByInterventionTypeIn(List.of(type.toUpperCase())));
        }
        return ResponseEntity.ok(eventRepo.findAll());
    }

    @GetMapping("/range")
    public ResponseEntity<List<MaintenanceEvent>> getByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(
                eventRepo.findByEventDateBetweenOrderByEventDateAsc(from, to));
    }

    @GetMapping("/{machineCode}/breakdowns")
    public ResponseEntity<List<MaintenanceEvent>> getBreakdowns(
            @PathVariable String machineCode) {
        return ResponseEntity.ok(eventRepo.findBreakdownsByMachine(machineCode));
    }

    /**
     * Core dashboard action: technician marks a machine as fixed.
     *
     * Flow:
     *   1. Validate request body
     *   2. Persist event locally (H2/PostgreSQL)
     *   3. Forward to Python ML → log-fix endpoint → instant retrain
     *   4. Return the fresh prediction for that machine
     */
    @PostMapping("/log-fix")
    public ResponseEntity<Map<String, Object>> logFix(
            @Valid @RequestBody LogFixRequest req) {

        // 1. Persist locally
        MaintenanceEvent event = MaintenanceEvent.builder()
                .machineCode(req.getMachineCode())
                .eventDate(LocalDate.parse(req.getEventDate()))
                .interventionType(req.getInterventionType().toUpperCase())
                .durationH(req.getDurationH())
                .notes(req.getNotes())
                .technicianId(req.getTechnicianId())
                .source("dashboard")
                .loggedAt(LocalDateTime.now())
                .build();
        eventRepo.save(event);
        log.info("Event persisted: id={} machine={} type={} date={}",
                event.getId(), event.getMachineCode(),
                event.getInterventionType(), event.getEventDate());

        // 2. Forward to Python ML (triggers instant retrain there)
        Map<String, Object> mlResult = mlClient.logFixAndRetrain(
                req.getMachineCode(),
                req.getEventDate(),
                req.getInterventionType(),
                req.getDurationH(),
                req.getNotes()
        );

        return ResponseEntity.ok(Map.of(
                "eventId",   event.getId(),
                "status",    "logged",
                "mlRetrain", mlResult
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteEvent(@PathVariable Long id) {
        if (!eventRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        eventRepo.deleteById(id);
        log.info("Event {} deleted", id);
        return ResponseEntity.ok(ApiResponse.ok("Event deleted"));
    }
}
