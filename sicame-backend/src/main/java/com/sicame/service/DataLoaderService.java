package com.sicame.service;

import com.sicame.model.Equipment;
import com.sicame.model.MaintenanceEvent;
import com.sicame.repository.EquipmentRepository;
import com.sicame.repository.MaintenanceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs at application startup.
 * Imports mh2.csv → maintenance_events table
 * Imports Equipement.csv → equipment table
 * Both imports are idempotent: they skip if data already exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataLoaderService implements CommandLineRunner {

    private final MaintenanceEventRepository eventRepo;
    private final EquipmentRepository        equipmentRepo;

    @Value("${sicame.data.mh-csv:./data/mh2.csv}")
    private String mhCsvPath;

    @Value("${sicame.data.eq-csv:./data/Equipement.csv}")
    private String eqCsvPath;

    @Override
    public void run(String... args) {
        log.info("=== DataLoader: starting CSV import ===");
        loadEquipment();
        loadMaintenanceHistory();
        log.info("=== DataLoader: done. Events={}, Equipment={}",
                eventRepo.count(), equipmentRepo.count());
    }

    // ── Equipment CSV ────────────────────────────────────────

    @Transactional
    public void loadEquipment() {
        if (equipmentRepo.count() > 0) {
            log.info("Equipment already loaded ({} rows) — skipping", equipmentRepo.count());
            return;
        }

        Path path = resolvePath(eqCsvPath, "Equipement.csv");
        if (path == null) { log.warn("Equipement.csv not found — skipping equipment load"); return; }

        List<Equipment> batch   = new ArrayList<>();
        String          category = "Unknown";

        try (BufferedReader br = Files.newBufferedReader(path)) {
            br.readLine(); // skip header
            String line;

            while ((line = br.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 8) continue;

                // Column 0 = category (forward-fill)
                String rawCat = clean(cols[0]);
                if (!rawCat.isEmpty() && !rawCat.equalsIgnoreCase("ListeDesEquipements")) {
                    category = rawCat;
                }

                String code = clean(cols[3]);
                String name = clean(cols[5]);
                if (code.isEmpty() || code.equalsIgnoreCase("CODEREF")) continue;

                double power = 5.0;
                try { power = Double.parseDouble(clean(cols[7])); } catch (Exception ignored) {}

                Integer qty = null;
                try { qty = Integer.parseInt(clean(cols[6])); } catch (Exception ignored) {}

                batch.add(Equipment.builder()
                        .machineCode(code)
                        .name(name)
                        .category(category)
                        .quantity(qty)
                        .powerKw(power)
                        .idealHoursPerDay(8.0)
                        .build());
            }

            equipmentRepo.saveAll(batch);
            log.info("Equipment loaded: {} records", batch.size());

        } catch (IOException e) {
            log.error("Failed to read Equipement.csv: {}", e.getMessage());
        }
    }

    // ── Maintenance history CSV ──────────────────────────────

    @Transactional
    public void loadMaintenanceHistory() {
        if (eventRepo.countBySource("historical") > 0) {
            log.info("Historical events already loaded ({} rows) — skipping",
                    eventRepo.countBySource("historical"));
            return;
        }

        Path path = resolvePath(mhCsvPath, "mh2.csv");
        if (path == null) { log.warn("mh2.csv not found — skipping maintenance history load"); return; }

        List<MaintenanceEvent> batch = new ArrayList<>();
        DateTimeFormatter      fmt   = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line;

            while ((line = br.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 3) continue;

                String dateStr = clean(cols[0]);
                String code    = clean(cols[1]);
                String type    = clean(cols[2]);

                if (dateStr.isEmpty() || code.isEmpty() || type.isEmpty()) continue;

                LocalDate date;
                try { date = LocalDate.parse(dateStr, fmt); }
                catch (Exception e) { continue; }

                Double duration = null;
                if (cols.length >= 4) {
                    String d = clean(cols[3]);
                    if (!d.isEmpty() && !d.equalsIgnoreCase("NULL") && !d.equals("\\N")) {
                        try { duration = Double.parseDouble(d); } catch (Exception ignored) {}
                    }
                }

                batch.add(MaintenanceEvent.builder()
                        .eventDate(date)
                        .machineCode(code)
                        .interventionType(type.toUpperCase())
                        .durationH(duration)
                        .source("historical")
                        .loggedAt(LocalDateTime.now())
                        .build());
            }

            eventRepo.saveAll(batch);
            log.info("Maintenance history loaded: {} records", batch.size());

        } catch (IOException e) {
            log.error("Failed to read mh2.csv: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private Path resolvePath(String configured, String filename) {
        // 1. Configured path
        Path p = Path.of(configured);
        if (Files.exists(p)) return p;
        // 2. ./data/ folder
        p = Path.of("data", filename);
        if (Files.exists(p)) return p;
        // 3. classpath resources
        p = Path.of("src", "main", "resources", filename);
        if (Files.exists(p)) return p;
        return null;
    }

    private String clean(String s) {
        if (s == null) return "";
        return s.trim().replace("\"", "").replace("\uFEFF", "");
    }

    /** Minimal CSV tokeniser — handles double-quoted fields. */
    private String[] splitCsv(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuote     = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens.toArray(new String[0]);
    }
}
