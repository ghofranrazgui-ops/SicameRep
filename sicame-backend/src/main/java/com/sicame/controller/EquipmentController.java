package com.sicame.controller;

import com.sicame.model.Equipment;
import com.sicame.repository.EquipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Equipment / machine catalogue endpoints.
 *
 * GET /api/equipment                   — all machines (optional ?category=)
 * GET /api/equipment/{machineCode}     — single machine
 * GET /api/equipment/categories        — distinct category list
 */
@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentRepository equipmentRepo;

    @GetMapping
    public ResponseEntity<List<Equipment>> getAll(
            @RequestParam(required = false) String category) {
        if (category != null) {
            return ResponseEntity.ok(
                    equipmentRepo.findByCategoryContainingIgnoreCase(category));
        }
        return ResponseEntity.ok(equipmentRepo.findAll());
    }

    @GetMapping("/{machineCode}")
    public ResponseEntity<Equipment> getOne(@PathVariable String machineCode) {
        return equipmentRepo.findById(machineCode)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        List<String> cats = equipmentRepo.findAll().stream()
                .map(Equipment::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return ResponseEntity.ok(cats);
    }
}
