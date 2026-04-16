package com.example.productcatalog;

import com.example.productcatalog.agent.ProductCatalogAgent;
import com.google.adk.a2a.executor.AgentExecutorConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.sessions.InMemorySessionService;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.spec.Message;
import io.reactivex.rxjava3.core.Single;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Produces the {@link AgentExecutor} that runs the product catalog agent over A2A. */
@ApplicationScoped
public class AgentExecutorProducer {

  private static final Logger LOG = Logger.getLogger(AgentExecutorProducer.class);

  @ConfigProperty(name = "my.adk.app.name", defaultValue = "product-catalog")
  String appName;

  @ConfigProperty(name = "GOOGLE_API_KEY", defaultValue = "")
  String googleApiKey;

  /** ADK {@code beforeExecuteCallback}: {@code true} skips execution (task canceled); {@code false} runs the agent. */
  @Produces
  @ApplicationScoped
  public AgentExecutor agentExecutor() {
    String apiKey = resolveGeminiApiKey();
    AgentExecutorConfig execConfig =
        AgentExecutorConfig.builder()
            .beforeExecuteCallback(
                (RequestContext ctx) -> {
                  Message msg = ctx.getMessage();
                  int partCount = msg != null && msg.getParts() != null ? msg.getParts().size() : 0;
                  LOG.infof(
                      "Server received A2A message: taskId=%s, contextId=%s, partCount=%d",
                      ctx.getTaskId(), ctx.getContextId(), partCount);
                  return Single.just(false);
                })
            .build();
    return new com.google.adk.a2a.executor.AgentExecutor.Builder()
        .agent(ProductCatalogAgent.createRootAgent(apiKey))
        .appName(appName)
        .sessionService(new InMemorySessionService())
        .artifactService(new InMemoryArtifactService())
        .agentExecutorConfig(execConfig)
        .build();
  }

  private String resolveGeminiApiKey() {
    String key = GoogleApiKeySupport.resolve(googleApiKey);
    if (!key.isBlank()) {
      return key;
    }
    throw new IllegalStateException(
        "Set GOOGLE_API_KEY in the environment, in a .env file next to the process, or in Quarkus config.");
  }
}
