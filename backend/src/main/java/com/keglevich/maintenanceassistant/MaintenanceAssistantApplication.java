package com.keglevich.maintenanceassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the maintenance-assistant backend.
 *
 * <p>One deployable unit, internally split into the modules {@code ingestion}, {@code query} and
 * {@code web} (ADR-001). The module boundary drawn here is the seam along which {@code ingestion}
 * is extracted into its own service in Phase 2.
 */
@SpringBootApplication
// Picks up the @ConfigurationProperties records inside the modules, so each one declares its own
// configuration next to the code that uses it rather than in a central config class.
@ConfigurationPropertiesScan
public class MaintenanceAssistantApplication {

  /**
   * Starts the application.
   *
   * <p>Nothing is configured here on purpose: component and
   * {@code @ConfigurationProperties} scanning are declared on the class above, so a reader looking
   * for what the application switches on finds it in one place rather than split between an
   * annotation and a builder chain.
   */
  public static void main(String[] args) {
    SpringApplication.run(MaintenanceAssistantApplication.class, args);
  }
}
