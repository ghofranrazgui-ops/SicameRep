package com.sicame.repository;

import com.sicame.model.MaintenanceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MaintenanceEventRepository extends JpaRepository<MaintenanceEvent, Long> {

    List<MaintenanceEvent> findByMachineCodeOrderByEventDateDesc(String machineCode);

    List<MaintenanceEvent> findByEventDateBetweenOrderByEventDateAsc(
            LocalDate from, LocalDate to);

    List<MaintenanceEvent> findByMachineCodeAndEventDateBetweenOrderByEventDateAsc(
            String machineCode, LocalDate from, LocalDate to);

    @Query("SELECT e FROM MaintenanceEvent e WHERE e.interventionType IN :types ORDER BY e.eventDate DESC")
    List<MaintenanceEvent> findByInterventionTypeIn(@Param("types") List<String> types);

    @Query("""
        SELECT e FROM MaintenanceEvent e
        WHERE e.machineCode = :code
          AND e.interventionType IN ('C','IC','CP')
        ORDER BY e.eventDate DESC
    """)
    List<MaintenanceEvent> findBreakdownsByMachine(@Param("code") String machineCode);

    Optional<MaintenanceEvent> findTopByMachineCodeAndInterventionTypeInOrderByEventDateDesc(
            String machineCode, List<String> types);

    long countBySource(String source);

    boolean existsByMachineCode(String machineCode);

    @Query("""
        SELECT e.machineCode, e.eventDate, COALESCE(SUM(e.durationH), 0)
        FROM MaintenanceEvent e
        WHERE e.interventionType IN ('C','IC','CP')
          AND e.eventDate BETWEEN :from AND :to
        GROUP BY e.machineCode, e.eventDate
        ORDER BY e.eventDate
    """)
    List<Object[]> findDailyBreakdownHours(@Param("from") LocalDate from,
                                            @Param("to")   LocalDate to);

    @Query("""
        SELECT e.machineCode, COALESCE(SUM(e.durationH), 0)
        FROM MaintenanceEvent e
        WHERE e.interventionType IN ('C','IC','CP')
          AND e.eventDate BETWEEN :from AND :to
        GROUP BY e.machineCode
    """)
    List<Object[]> findTotalBreakdownHoursByMachine(@Param("from") LocalDate from,
                                                     @Param("to")   LocalDate to);
}
