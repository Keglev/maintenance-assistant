package com.keglevich.maintenanceassistant.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The machines a question can be asked about.
 *
 * <p>In the query module rather than in ingestion, because that is what it is <em>for</em>: search
 * is scoped to exactly one machine (DECISIONS.txt), {@link QueryService} takes a machine id, and
 * without a way to list them a client would have to know UUIDs it has no way to learn. Machine
 * master data is written by a Flyway seed and by nothing at runtime, so there is no writing side to
 * put this next to.
 *
 * <p>Read-only and deliberately thin: enough to fill a picker and label a result, not a master-data
 * API. Manufacturer, year and control type exist on the row and are not returned — nothing in the
 * UI asks for them, and an endpoint that returns everything is one that has to keep returning it.
 */
@Service
public class MachineCatalog {

    private final JdbcClient jdbc;

    MachineCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every machine, ordered by the plant identifier.
     *
     * <p>Unpaged on purpose: this is a fixed-size plant, ten rows in the demo and tens in the
     * setting it models. Paging a picker that fits on one screen would be ceremony.
     */
    public List<Machine> findAll() {
        return jdbc.sql("""
                        SELECT id, machine_no, name, type, location
                        FROM machine
                        ORDER BY machine_no
                        """)
                .query((rs, rowNum) -> new Machine(
                        rs.getObject("id", UUID.class),
                        rs.getString("machine_no"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("location")))
                .list();
    }

    /**
     * One machine by its plant identifier, or empty.
     *
     * <p>By {@code machineNo} and not by id, because the caller that needs this is addressing a
     * machine the way a person does — see {@code /api/machines/{machineNo}/examples}, whose
     * resource file is hand-authored against plant identifiers (ADR-011).
     */
    public Optional<Machine> findByMachineNo(String machineNo) {
        return jdbc.sql("""
                        SELECT id, machine_no, name, type, location
                        FROM machine
                        WHERE machine_no = :machineNo
                        """)
                .param("machineNo", machineNo)
                .query((rs, rowNum) -> new Machine(
                        rs.getObject("id", UUID.class),
                        rs.getString("machine_no"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("location")))
                .optional();
    }

    /**
     * How many protocols this machine has that a question could actually reach.
     *
     * <p><b>{@code deleted_at IS NULL} is the whole point of this method.</b> It is the same
     * predicate {@link ChunkRetriever}'s statement carries, and the two must not disagree: a count
     * that included soft-deleted rows would tell a user that evidence exists which retrieval can
     * never return, which is a worse answer than no number at all. Measured on 2026-08-26, the
     * difference is real rather than theoretical — 50 soft-deleted protocols locally and 2 in
     * production would have been counted.
     *
     * <p>"Live" is therefore spelled in two SQL statements, here and in the retriever. That
     * duplication is recorded rather than hidden; hoisting it is a refactor with no caller asking
     * for it yet.
     */
    public int countLiveProtocols(UUID machineId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM protocol
                        WHERE machine_id = :machineId
                          AND deleted_at IS NULL
                        """)
                .param("machineId", machineId)
                .query(Integer.class)
                .single();
    }

    /**
     * @param machineNo the plant-facing identifier (PR-03) — what people say out loud, and what a
     *                  picker must show, because nobody on a shop floor knows a UUID
     * @param name      the human name (Presse 3)
     */
    public record Machine(UUID id, String machineNo, String name, String type, String location) {
    }
}
