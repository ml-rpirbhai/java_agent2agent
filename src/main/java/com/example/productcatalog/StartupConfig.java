package com.example.productcatalog;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Application startup: validates Gemini API configuration and registers Jackson modules for Vert.x
 * JSON.
 */
@ApplicationScoped
public class StartupConfig {

  @ConfigProperty(name = "GOOGLE_API_KEY", defaultValue = "")
  String googleApiKey;

  void onStart(@Observes StartupEvent ev) {
    if (GoogleApiKeySupport.resolve(googleApiKey).isBlank()) {
      throw new IllegalStateException(
          "GOOGLE_API_KEY must be set (environment, .env, or Quarkus config)");
    }
    DatabindCodec.mapper().registerModule(new JavaTimeModule());
  }
}
