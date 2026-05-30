package com.sicame.service;

import com.sicame.model.Equipment;
import com.sicame.model.MaintenanceEvent;
import com.sicame.repository.EquipmentRepository;
import com.sicame.repository.MaintenanceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculates real vs ideal productivity and energy/CO2 metrics.
 *
 * Business rules (from SICAME PDF Annex A1):
 *   Ideal hours/day/machine = shiftHours (8h, 1 shift)
 *   Actual hours            = Ideal − breakdown downtime
 *   Energy ideal   (kWh)   = powerKw × ideal hours
 *   Energy consumed (kWh)  = powerKw × actual hours
 *   Energy wasted  (kWh)   = powerKw × idlePowerFactor × lost hours
 *   CO2 impact     (kg)    = energy wasted × co2Factor
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductivityService {

    private final MaintenanceEventRepository eventRepo;
    private final EquipmentRepository        equipmentRepo;

    @Value("${sicame.productivity.shift-hours:8}")
    private double shiftHours;

    @Value("${sicame.productivity.idle-power-factor:0.30}")
    private double idlePowerFactor;

    @Value("${sicame.productivity.co2-factor:0.233}")
    private double co2Factor;

    // ── Global productivity ──────────────────────────────────

    public Map<String, Object> getGlobalProductivity(LocalDate from, LocalDate to) {
        List<MaintenanceEvent> events = eventRepo.findByEventDateBetweenOrderByEventDateAsc(from, to);
        Map<String, Equipment> eqMap  = loadEquipmentMap();

        // daily lost hours: date → machineCode → hours
        Map<LocalDate, Map<String, Double>> lostByDay = buildDailyLostMap(events);

        List<Map<String, Object>> dailySeries = new ArrayList<>();
        double totalIdeal = 0, totalActual = 0, totalLost = 0;
        double totalEI = 0, totalEA = 0, totalEW = 0;

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWeekend(d)) continue;

            Map<String, Double> machineMap = lostByDay.getOrDefault(d, Map.of());
            double dayIdeal = 0, dayActual = 0, dayLost = 0;
            double dayEI = 0, dayEA = 0, dayEW = 0;

            for (Equipment eq : eqMap.values()) {
                double kw    = safeKw(eq);
                double ideal  = shiftHours;
                double lost   = Math.min(machineMap.getOrDefault(eq.getMachineCode(), 0.0), ideal);
                double actual = ideal - lost;

                dayIdeal  += ideal;  dayActual += actual;  dayLost += lost;
                dayEI     += kw * ideal;
                dayEA     += kw * actual;
                dayEW     += kw * idlePowerFactor * lost;
            }

            totalIdeal  += dayIdeal;  totalActual += dayActual;  totalLost += dayLost;
            totalEI     += dayEI;     totalEA     += dayEA;      totalEW   += dayEW;

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date",              d.toString());
            day.put("idealHours",        r(dayIdeal));
            day.put("actualHours",       r(dayActual));
            day.put("lostHours",         r(dayLost));
            day.put("productivityPct",   pct(dayActual, dayIdeal));
            day.put("energyConsumedKwh", r(dayEA));
            day.put("energyWastedKwh",   r(dayEW));
            day.put("hadBreakdown",      dayLost > 0);
            dailySeries.add(day);
        }

        // Per-machine productivity over period
        Map<String, Object> byMachine = new LinkedHashMap<>();
        for (Equipment eq : eqMap.values()) {
            double totalLostForMachine = events.stream()
                    .filter(e -> e.getMachineCode().equals(eq.getMachineCode()) && e.isCorrective())
                    .mapToDouble(e -> e.getDurationH() != null ? e.getDurationH() : 0)
                    .sum();
            double idealForMachine = dailySeries.size() * shiftHours;
            byMachine.put(eq.getMachineCode(), pct(idealForMachine - totalLostForMachine, idealForMachine));
        }

        // Per-category productivity
        Map<String, Object> byCategory = eqMap.values().stream()
                .collect(Collectors.groupingBy(
                        eq -> eq.getCategory() != null ? eq.getCategory() : "Unknown",
                        Collectors.collectingAndThen(Collectors.toList(), eqList -> {
                            double lost = eqList.stream().mapToDouble(eq ->
                                    events.stream()
                                            .filter(e -> e.getMachineCode().equals(eq.getMachineCode()) && e.isCorrective())
                                            .mapToDouble(e -> e.getDurationH() != null ? e.getDurationH() : 0)
                                            .sum()).sum();
                            double ideal = (double) eqList.size() * dailySeries.size() * shiftHours;
                            return pct(ideal - lost, ideal);
                        })
                ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailySeries",            dailySeries);
        result.put("productivityByMachine",  byMachine);
        result.put("productivityByCategory", byCategory);
        result.put("overallProductivityPct", pct(totalActual, totalIdeal));
        result.put("totalIdealHours",        r(totalIdeal));
        result.put("totalActualHours",       r(totalActual));
        result.put("totalLostHours",         r(totalLost));
        result.put("totalEnergyIdealKwh",    r(totalEI));
        result.put("totalEnergyConsumedKwh", r(totalEA));
        result.put("totalEnergyWastedKwh",   r(totalEW));
        result.put("carbonImpactKg",         r(totalEW * co2Factor));
        result.put("period",                 from + " to " + to);
        return result;
    }

    // ── Single machine productivity ──────────────────────────

    public Map<String, Object> getMachineProductivity(String machineCode,
                                                      LocalDate from, LocalDate to) {
        Equipment eq = equipmentRepo.findById(machineCode).orElse(null);
        List<MaintenanceEvent> events = eventRepo
                .findByMachineCodeAndEventDateBetweenOrderByEventDateAsc(machineCode, from, to);

        Map<LocalDate, Double> lostByDay = buildDailyLostForMachine(events);
        double kw = eq != null ? safeKw(eq) : 5.0;

        List<Map<String, Object>> dailySeries = new ArrayList<>();
        double totalIdeal = 0, totalActual = 0, totalLost = 0;
        double totalEI = 0, totalEA = 0, totalEW = 0;

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWeekend(d)) continue;

            double ideal  = shiftHours;
            double lost   = Math.min(lostByDay.getOrDefault(d, 0.0), ideal);
            double actual = ideal - lost;
            double ei = kw * ideal, ea = kw * actual, ew = kw * idlePowerFactor * lost;

            totalIdeal += ideal; totalActual += actual; totalLost += lost;
            totalEI   += ei;    totalEA     += ea;     totalEW   += ew;

            final LocalDate currentDay = d;
            String bdType = events.stream()
                    .filter(e -> e.getEventDate().equals(currentDay) && e.isCorrective())
                    .map(MaintenanceEvent::getInterventionType)
                    .findFirst().orElse(null);

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date",              d.toString());
            day.put("idealHours",        r(ideal));
            day.put("actualHours",       r(actual));
            day.put("lostHours",         r(lost));
            day.put("productivityPct",   pct(actual, ideal));
            day.put("energyConsumedKwh", r(ea));
            day.put("energyWastedKwh",   r(ew));
            day.put("hadBreakdown",      lost > 0);
            day.put("breakdownType",     bdType);
            dailySeries.add(day);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("machineCode",      machineCode);
        result.put("machineName",      eq != null ? eq.getName() : machineCode);
        result.put("category",         eq != null ? eq.getCategory() : "Unknown");
        result.put("powerKw",          kw);
        result.put("dailySeries",      dailySeries);
        result.put("idealHoursTotal",  r(totalIdeal));
        result.put("actualHoursTotal", r(totalActual));
        result.put("lostHoursTotal",   r(totalLost));
        result.put("productivityPct",  pct(totalActual, totalIdeal));
        result.put("energyIdealKwh",   r(totalEI));
        result.put("energyConsumedKwh",r(totalEA));
        result.put("energyWastedKwh",  r(totalEW));
        result.put("carbonImpactKg",   r(totalEW * co2Factor));
        result.put("period",           from + " to " + to);
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────

    private Map<String, Equipment> loadEquipmentMap() {
        Map<String, Equipment> map = new LinkedHashMap<>();
        equipmentRepo.findAll().forEach(eq -> map.put(eq.getMachineCode(), eq));
        return map;
    }

    private Map<LocalDate, Map<String, Double>> buildDailyLostMap(List<MaintenanceEvent> events) {
        Map<LocalDate, Map<String, Double>> map = new HashMap<>();
        events.stream().filter(MaintenanceEvent::isCorrective).forEach(e -> {
            double h = e.getDurationH() != null ? e.getDurationH() : 0;
            map.computeIfAbsent(e.getEventDate(), k -> new HashMap<>())
                    .merge(e.getMachineCode(), h, Double::sum);
        });
        return map;
    }

    private Map<LocalDate, Double> buildDailyLostForMachine(List<MaintenanceEvent> events) {
        Map<LocalDate, Double> map = new HashMap<>();
        events.stream().filter(MaintenanceEvent::isCorrective).forEach(e -> {
            double h = e.getDurationH() != null ? e.getDurationH() : 0;
            map.merge(e.getEventDate(), h, Double::sum);
        });
        return map;
    }

    private boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private double pct(double actual, double ideal) {
        if (ideal <= 0) return 100.0;
        return r(Math.min(100.0, (actual / ideal) * 100.0));
    }

    private double r(double v) { return Math.round(v * 100.0) / 100.0; }

    private double safeKw(Equipment eq) {
        return (eq.getPowerKw() != null && eq.getPowerKw() > 0) ? eq.getPowerKw() : 5.0;
    }
}