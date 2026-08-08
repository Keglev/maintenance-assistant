package com.keglevich.maintenanceassistant.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
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
     * @param machineNo the plant-facing identifier (PR-03) — what people say out loud, and what a
     *                  picker must show, because nobody on a shop floor knows a UUID
     * @param name      the human name (Presse 3)
     */
    public record Machine(UUID id, String machineNo, String name, String type, String location) {
    }
}
