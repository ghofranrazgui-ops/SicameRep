package com.sicame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * Request body for POST /api/events/log-fix.
 * Sent by the Angular dashboard when a technician logs an intervention.
 */
@Data
public class LogFixRequest {

    @NotBlank(message = "machineCode is required")
    private String machineCode;

    @NotBlank(message = "eventDate is required")
    private String eventDate;           // ISO date: "2026-05-03"

    @NotBlank(message = "interventionType is required")
    private String interventionType;    // C | P | G | Q | IC | PP | INS

    @Positive(message = "durationH must be positive")
    private double durationH;

    private String notes;
    private String technicianId;
}
