package com.example.productcatalog;

import io.github.cdimascio.dotenv.Dotenv;

/** Resolves Gemini API key for Quarkus the same way as standalone {@code ProductCatalogAgent}. */
public final class GoogleApiKeySupport {

  /**
   * Uses non-blank {@code fromQuarkusConfig} first, then {@code GOOGLE_API_KEY} in the environment,
   * then {@code GOOGLE_API_KEY} from a {@code .env} file in the working directory.
   */
  public static String resolve(String fromQuarkusConfig) {
    if (fromQuarkusConfig != null && !fromQuarkusConfig.isBlank()) {
      return fromQuarkusConfig.strip();
    }
    String env = System.getenv("GOOGLE_API_KEY");
    if (env != null && !env.isBlank()) {
      return env.strip();
    }
    String fromEnvFile =
        Dotenv.configure().ignoreIfMalformed().ignoreIfMissing().load().get("GOOGLE_API_KEY");
    return fromEnvFile != null ? fromEnvFile.strip() : "";
  }

  private GoogleApiKeySupport() {}
}
