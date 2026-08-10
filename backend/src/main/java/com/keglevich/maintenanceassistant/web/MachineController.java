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
 * the question unaskable.
 *
 * <p><b>And to the admin, since ADR-006 gave that role a shop-floor function.</b> It cannot ask a
 * question and must not be able to, but the moderation view filters the corpus by machine, and a
 * machine filter needs the machine list. Refusing it produced a live defect: the admin's landing
 * page rendered "Maschinenliste nicht verfügbar" because the first call it made was one the role was
 * correctly forbidden to make. This is plant metadata — ten rows of identifier, name and location,
 * no protocol content and nothing a protocol says — so widening the read is proportionate where
 * widening {@code POST /api/query} would not be.
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
    @PreAuthorize("hasAnyRole('OPERATOR', 'TECHNIKER', 'SCHICHTLEITER', 'ADMIN')")
    @Operation(summary = "List the machines a question can be asked about",
            description = "Ordered by plant identifier. Unpaged: this is a fixed-size plant. "
                    + "Readable by an administrator too — the moderation filter needs it, and "
                    + "plant metadata carries nothing a protocol says.")
    List<MachineCatalog.Machine> list() {
        return machines.findAll();
    }
}
