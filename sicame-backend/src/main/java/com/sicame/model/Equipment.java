package com.sicame.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Machine / equipment record from Equipement.csv.
 * Stores identity, category, power consumption and ideal operating hours.
 */
@Entity
@Table(name = "equipment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Equipment {

    @Id
    @Column(length = 20)
    private String machineCode;

    @Column(length = 200)
    private String name;

    @Column(length = 100)
    private String category;

    private Integer quantity;

    /**
     * Rated power consumption in kW during active operation.
     * Sourced from Equipement.csv "Consommation en KWh" column.
     * Defaults to 5.0 kW where data is missing.
     */
    @Builder.Default
    private Double powerKw = 5.0;

    /**
     * Fraction of powerKw consumed while machine is idle / broken down.
     * 30% idle draw is the SICAME engineering assumption.
     */
    @Builder.Default
    private Double idlePowerFactor = 0.30;

    /**
     * Ideal operational hours per day.
     * From SICAME PDF Annex A1: 8h/day, 1 shift (schedule 1/8).
     */
    @Builder.Default
    private Double idealHoursPerDay = 8.0;
}
