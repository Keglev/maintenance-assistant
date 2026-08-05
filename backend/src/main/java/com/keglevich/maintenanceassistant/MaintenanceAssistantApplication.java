package com.keglevich.maintenanceassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the maintenance-assistant backend.
 *
 * <p>One deployable unit, internally split into the modules {@code ingestion}, {@code query} and
 * {@code web} (ADR-001). The module boundary drawn here is the seam along which {@code ingestion}
 * is extracted into its own service in Phase 2.
 */
@SpringBootApplication
public class MaintenanceAssistantApplication {

  public static void main(String[] args) {
    SpringApplication.run(MaintenanceAssistantApplication.class, args);
  }
}
