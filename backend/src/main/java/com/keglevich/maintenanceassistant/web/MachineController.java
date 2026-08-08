package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.query.MachineCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The machine list behind the search view's picker.
 *
 * <p>It exists because {@code POST /api/query} takes a machine <em>id</em> and search is scoped to
 * exactly one machine (DECISIONS.txt). Without this, a client would need UUIDs it has no way to
 * learn, which is why the previous PR recorded the gap rather than working around it.
 *
 * <p>Open to the same three shop-floor roles as the query itself: knowing which machines the plant
 * has is a precondition of asking about one, so gating it more tightly than the question would make
 * the question unaskable. Admin is excluded for the same reason it is on the query — it is a
 * Keycloak IT role, not a shop-floor one.
 */
@RestController
@RequestMapping("/api/machines")
@Tag(name = "Query", description = "Question answering over the indexed maintenance protocols")
class MachineController {

    private final MachineCatalog machines;

    MachineController(MachineCatalog machines) {
        this.machines = machines;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'TECHNIKER', 'SCHICHTLEITER')")
    @Operation(summary = "List the machines a question can be asked about",
            description = "Ordered by plant identifier. Unpaged: this is a fixed-size plant.")
    List<MachineCatalog.Machine> list() {
        return machines.findAll();
    }
}
