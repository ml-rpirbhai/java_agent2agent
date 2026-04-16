package com.example.productcatalog.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.ProxyOptions;
import com.google.genai.types.ProxyType;
import io.github.cdimascio.dotenv.Dotenv;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Product catalog LLM agent and {@code getProductInfo} tool (port of {@code product_catalog.py}).
 *
 * <p>Static tools are bound with {@code FunctionTool.create(Class, String)}; do not instantiate
 * this class.
 */
public final class ProductCatalogAgent {

  private static final Map<String, String> PRODUCT_CATALOG = new LinkedHashMap<>();

  static {
    PRODUCT_CATALOG.put(
        "iphone 15 pro",
        "iPhone 15 Pro, $999, Low Stock (8 units), 128GB, Titanium finish");
    PRODUCT_CATALOG.put(
        "samsung galaxy s24",
        "Samsung Galaxy S24, $799, In Stock (31 units), 256GB, Phantom Black");
    PRODUCT_CATALOG.put(
        "dell xps 15",
        "Dell XPS 15, $1,299, In Stock (45 units), 15.6\" display, 16GB RAM, 512GB SSD");
    PRODUCT_CATALOG.put(
        "macbook pro 14",
        "MacBook Pro 14\", $1,999, In Stock (22 units), M3 Pro chip, 18GB RAM, 512GB SSD");
    PRODUCT_CATALOG.put(
        "sony wh-1000xm5",
        "Sony WH-1000XM5 Headphones, $399, In Stock (67 units), Noise-canceling, 30hr battery");
    PRODUCT_CATALOG.put("ipad air", "iPad Air, $599, In Stock (28 units), 10.9\" display, 64GB");
    PRODUCT_CATALOG.put(
        "lg ultrawide 34",
        "LG UltraWide 34\" Monitor, $499, Out of Stock, Expected: Next week");
  }

  private static final String MODEL_NAME = "gemini-2.5-flash-lite";

  private static final String AGENT_INSTRUCTION =
      """
      You are a product catalog specialist from an external vendor.
      When asked about products, use the getProductInfo tool to fetch data from the catalog.
      Provide clear, accurate product information including price, availability, and specs.
      If asked about multiple products, look up each one.
      Be professional and helpful.
      """;

  @Schema(
      description =
          "Look up product information by name (e.g. \"iPhone 15 Pro\", \"MacBook Pro 14\").")
  public static String getProductInfo(
      @Schema(
              name = "product_name",
              description = "Name of the product to look up in the vendor catalog.")
          String productName) {
    String key = productName.toLowerCase(Locale.ROOT).strip();
    if (PRODUCT_CATALOG.containsKey(key)) {
      return "Product: " + PRODUCT_CATALOG.get(key);
    }
    String available = String.join(", ", PRODUCT_CATALOG.keySet());
    return "Sorry, I don't have information for "
        + productName
        + ". Available products: "
        + available;
  }

  /**
   * Standalone: run one turn with {@link InMemoryRunner}. Resolves {@code GOOGLE_API_KEY} from the
   * environment first, then from a {@code .env} file in the working directory (project root when
   * using {@code mvn exec:java}).
   */
  public static void main(String[] args) {
    String apiKey = resolveGoogleApiKeyForStandalone();
    if (apiKey.isBlank()) {
      System.err.println(
          "Set GOOGLE_API_KEY in the environment or in a .env file in the working directory.");
      System.exit(1);
    }
    String question =
        args.length > 0 ? String.join(" ", args) : "What is the price of the iPhone 15 Pro?";

    String appName = "product-catalog-standalone";
    String userId = "standalone-user";
    String sessionId = "standalone-session";
    SessionKey sessionKey = new SessionKey(appName, userId, sessionId);

    LlmAgent agent = createRootAgent(apiKey);
    InMemoryRunner runner = new InMemoryRunner(agent, appName);
    Content userMessage =
        Content.builder().role("user").parts(List.of(Part.fromText(question))).build();
    List<Event> events;
    try {
      runner.sessionService().createSession(sessionKey, Map.of()).blockingGet();
      events =
          runner
              .runAsync(sessionKey, userMessage)
              .timeout(4, TimeUnit.MINUTES)
              .toList()
              .blockingGet();
    } finally {
      runner.close().blockingAwait();
    }

    StringBuilder transcript = new StringBuilder();
    for (Event e : events) {
      if (e.content().isEmpty()) {
        continue;
      }
      String text = e.content().get().text();
      if (text == null || text.isBlank()) {
        continue;
      }
      transcript.append('[').append(e.author()).append("] ").append(text).append('\n');
    }
    System.out.print(transcript);
    if (transcript.isEmpty()) {
      System.err.println("No model text in events; check API key and model availability.");
      System.exit(1);
    }
  }

  private static String resolveGoogleApiKeyForStandalone() {
    String env = System.getenv("GOOGLE_API_KEY");
    if (env != null && !env.isBlank()) {
      return env.strip();
    }
    String fromEnvFile =
        Dotenv.configure().ignoreIfMalformed().ignoreIfMissing().load().get("GOOGLE_API_KEY");
    return fromEnvFile != null ? fromEnvFile.strip() : "";
  }

  /** Root {@link LlmAgent} for Quarkus A2A or {@link #main(String[])}. */
  public static LlmAgent createRootAgent(String googleApiKey) {
    String key = Objects.requireNonNull(googleApiKey, "googleApiKey").strip();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("googleApiKey must not be blank");
    }
    HttpOptions httpOptions =
        HttpOptions.builder()
            .timeout(180_000)
            .retryOptions(
                HttpRetryOptions.builder()
                    .attempts(3)
                    .initialDelay(1.0)
                    .httpStatusCodes(List.of(429, 500, 503, 504))
                    .build())
            .build();
    Client.Builder clientBuilder = Client.builder().apiKey(key).httpOptions(httpOptions);
    clientOptionsFromHttpProxyEnv().ifPresent(clientBuilder::clientOptions);
    Gemini gemini =
        Gemini.builder().modelName(MODEL_NAME).apiClient(clientBuilder.build()).build();
    return LlmAgent.builder()
        .model(gemini)
        .name("product_catalog_agent")
        .description(
            "External vendor's catalog agent that provides product information and availability")
        .instruction(AGENT_INSTRUCTION)
        .tools(FunctionTool.create(ProductCatalogAgent.class, "getProductInfo"))
        .build();
  }

  /**
   * Uses {@code HTTPS_PROXY}/{@code https_proxy}/{@code HTTP_PROXY}/{@code http_proxy} when set,
   * so the GenAI client reaches Google APIs through corporate proxies (OkHttp does not
   * automatically mirror {@code curl}'s proxy behavior).
   */
  private static Optional<ClientOptions> clientOptionsFromHttpProxyEnv() {
    String raw =
        firstNonBlank(
            System.getenv("HTTPS_PROXY"),
            System.getenv("https_proxy"),
            System.getenv("HTTP_PROXY"),
            System.getenv("http_proxy"));
    if (raw == null) {
      return Optional.empty();
    }
    try {
      URI u = URI.create(raw);
      String host = u.getHost();
      if (host == null) {
        return Optional.empty();
      }
      int port = u.getPort();
      if (port < 0) {
        port = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
      }
      ProxyOptions.Builder proxy =
          ProxyOptions.builder()
              .type(new ProxyType(ProxyType.Known.HTTP))
              .host(host)
              .port(port);
      String userInfo = u.getUserInfo();
      if (userInfo != null && !userInfo.isBlank()) {
        int c = userInfo.indexOf(':');
        if (c > 0) {
          proxy.username(
              URLDecoder.decode(userInfo.substring(0, c), StandardCharsets.UTF_8));
          proxy.password(
              URLDecoder.decode(userInfo.substring(c + 1), StandardCharsets.UTF_8));
        } else {
          proxy.username(URLDecoder.decode(userInfo, StandardCharsets.UTF_8));
        }
      }
      return Optional.of(ClientOptions.builder().proxyOptions(proxy.build()).build());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v.strip();
      }
    }
    return null;
  }

  private ProductCatalogAgent() {}
}
