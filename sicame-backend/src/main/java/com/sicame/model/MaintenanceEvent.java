package com.sicame.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted maintenance intervention record.
 * Covers both historical CSV records and new events logged from the dashboard.
 *
 * Intervention type semantics (from SICAME field codes):
 *   C   = Corrective breakdown — machine failed, repaired
 *   P   = Preventive maintenance — scheduled service
 *   INS = Installation / commissioning
 *   Q   = Quality check / adjustment
 *   G   = Greasing / lubrication
 *   CP  = Corrective + Preventive combined
 *   PP  = Planned preventive
 *   IC  = Inspection corrective (near-miss finding)
 */
@Entity
@Table(
    name = "maintenance_events",
    indexes = {
        @Index(name = "idx_me_machine_date",  columnList = "machineCode, eventDate"),
        @Index(name = "idx_me_type",          columnList = "interventionType"),
        @Index(name = "idx_me_date",          columnList = "eventDate"),
        @Index(name = "idx_me_source",        columnList = "source")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate eventDate;

    @Column(nullable = false, length = 20)
    private String machineCode;

    @Column(nullable = false, length = 10)
    private String interventionType;

    /** Execution time in hours (can be null in original data). */
    private Double durationH;

    /**
     * Data origin:
     *   "historical" = loaded from mh2.csv on startup
     *   "dashboard"  = logged by a technician via the frontend
     */
    @Column(length = 20)
    @Builder.Default
    private String source = "historical";

    @Column(length = 500)
    private String notes;

    @Column(length = 50)
    private String technicianId;

    private LocalDateTime loggedAt;

    @PrePersist
    protected void prePersist() {
        if (loggedAt == null) loggedAt = LocalDateTime.now();
        if (interventionType != null) interventionType = interventionType.toUpperCase();
    }

    // ── Helpers ─────────────────────────────────────────────

    /** True for corrective events that represent actual machine breakdowns. */
    public boolean isCorrective() {
        return interventionType != null &&
               (interventionType.equals("C")  ||
                interventionType.equals("IC") ||
                interventionType.equals("CP"));
    }

    /** Human-readable label for the intervention type. */
    public String getInterventionLabel() {
        if (interventionType == null) return "Unknown";
        return switch (interventionType) {
            case "C"   -> "Corrective Breakdown";
            case "P"   -> "Preventive Maintenance";
            case "INS" -> "Installation / Commissioning";
            case "Q"   -> "Quality Check";
            case "G"   -> "Greasing / Lubrication";
            case "CP"  -> "Corrective + Preventive";
            case "PP"  -> "Planned Preventive";
            case "IC"  -> "Inspection Corrective";
            default    -> interventionType;
        };
    }

    /** Severity weight used for ML feature engineering. */
    public double getSeverityWeight() {
        if (interventionType == null) return 1.0;
        return switch (interventionType) {
            case "C"   -> 2.0;
            case "IC"  -> 1.8;
            case "CP"  -> 1.5;
            case "Q"   -> 1.2;
            case "PP"  -> 0.6;
            case "P"   -> 0.4;
            case "G"   -> 0.3;
            case "INS" -> 0.1;
            default    -> 1.0;
        };
    }
}
