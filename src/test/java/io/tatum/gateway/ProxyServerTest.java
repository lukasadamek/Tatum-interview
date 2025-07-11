package io.tatum.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
public class ProxyServerTest {
	
	private WebClientOptions options = new WebClientOptions()
			  .setSsl(true)
			  .setTrustAll(true) // ignore certificate validation
			  .setVerifyHost(false); // ignore hostname matching
	
	@BeforeEach
	public void deployVerticle2(Vertx vertx, VertxTestContext testContext) {
		vertx.deployVerticle(new MainVerticle())
			.onSuccess(id -> testContext.completeNow())
			.onFailure(testContext::failNow);
	}
	
	@Test
	public void testInvalidJsonRpcPayload(Vertx vertx, VertxTestContext ctx) {

	    JsonObject invalidPayload = new JsonObject()
	        .put("invalid", "data"); // missing 'method' field

	    WebClient client = WebClient.create(vertx, options);
	    client.post(8443, "localhost", "/rpc").ssl(true).sendJsonObject(invalidPayload)
	        .onSuccess(response -> ctx.verify(() -> {
	            assertEquals(400, response.statusCode());
	            assertTrue(response.bodyAsString().contains("Invalid request"));
	            ctx.completeNow();
	        }))
	        .onFailure(ctx::failNow);
	}
	
	@Test
	public void testRpcBatchRequest(Vertx vertx, VertxTestContext ctx) {
	    WebClient client = WebClient.create(vertx, options);

	    JsonArray batchPayload = new JsonArray()
	        .add(new JsonObject()
	            .put("jsonrpc", "2.0")
	            .put("method", "eth_blockNumber")
	            .put("params", new JsonArray())
	            .put("id", 1))
	        .add(new JsonObject()
	            .put("jsonrpc", "2.0")
	            .put("method", "web3_clientVersion")
	            .put("params", new JsonArray())
	            .put("id", 2));

	    client.post(8443, "localhost", "/rpc").ssl(true).sendJson(batchPayload)
	        .onSuccess(response -> ctx.verify(() -> {
	            assertEquals(200, response.statusCode());
	            assertTrue(response.bodyAsString().contains("result"));
	            ctx.completeNow();
	        }))
	        .onFailure(ctx::failNow);
	}
	
	@Test
	public void testInvalidEndpointReturns404(Vertx vertx, VertxTestContext ctx) {
	    WebClient client = WebClient.create(vertx, options);
	    client.get(8443, "localhost", "/nonexistent").ssl(true).send()
	        .onSuccess(response -> ctx.verify(() -> {
	            assertEquals(404, response.statusCode());
	            ctx.completeNow();
	        }))
	        .onFailure(ctx::failNow);
	}
	
	@Test
	public void testMetricsAfterRpcCall(Vertx vertx, VertxTestContext ctx) {
	    WebClient client = WebClient.create(vertx, options);
	    JsonObject payload = new JsonObject()
	        .put("jsonrpc", "2.0")
	        .put("method", "eth_blockNumber")
	        .put("params", new JsonArray())
	        .put("id", 1);

	    client.post(8443, "localhost", "/rpc").ssl(true).sendJsonObject(payload)
	        .compose(res -> client.get(8443, "localhost", "/metrics").ssl(true).send())
	        .onSuccess(metricsResponse -> ctx.verify(() -> {
	            JsonObject metrics = metricsResponse.bodyAsJsonObject();
	            assertTrue(metrics.containsKey("eth_blockNumber"));
	            assertTrue(metrics.getInteger("eth_blockNumber") > 0);
	            ctx.completeNow();
	        }))
	        .onFailure(ctx::failNow);
	}
	
	@Test
	public void testRpcProxyWhenBackendFails(Vertx vertx, VertxTestContext ctx) {
		JsonObject config = new JsonObject();
		config.put(MainVerticle.PARAM_ETHEREUM_NODE_URL, "https://ethereum.nonexistentnode.com"); // invalid ethereum url
		DeploymentOptions options = new DeploymentOptions().setConfig(config);

		vertx.deployVerticle(new MainVerticle(), options)
			.onSuccess(id -> ctx.completeNow())
			.onFailure(ctx::failNow);
		
		JsonObject payload = new JsonObject()
				.put("jsonrpc", "2.0")
				.put("method", "eth_blockNumber")
				.put("params", new JsonArray())
				.put("id", 1);

		WebClient client = WebClient.create(vertx,
				new WebClientOptions()
				.setSsl(true)
				.setTrustAll(true)
				.setVerifyHost(false));

		client.post(8443, "localhost", "/rpc").ssl(true).sendJsonObject(payload)
				.onSuccess(response -> ctx.verify(() -> {
					assertEquals(400, response.statusCode());
					assertTrue(response.bodyAsString().contains("Proxy error"));
					ctx.completeNow();
				})).onFailure(ctx::failNow);
	}

	@Test
	public void testRpcProxy(Vertx vertx, VertxTestContext ctx) {
		JsonObject payload = new JsonObject()
				.put("jsonrpc", "2.0")
				.put("method", "eth_blockNumber")
				.put("params", new JsonArray())
				.put("id", 1);
		
		WebClient client = WebClient.create(vertx, options);
		client.post(8443, "localhost", "/rpc").ssl(true).sendJsonObject(payload)
				.onSuccess(response -> ctx.verify(() -> {
					assertEquals(200, response.statusCode());
					assertTrue(response.bodyAsJsonObject().containsKey("result"));
					ctx.completeNow();
				})).onFailure(ctx::failNow);
	}

	@Test
	public void testMetricsEndpoint(Vertx vertx, VertxTestContext ctx) {
		WebClient client = WebClient.create(vertx, options);
		client.get(8443, "localhost", "/metrics")
		.ssl(true)
		.send()
		.onSuccess(response -> ctx.verify(() -> {
			assertEquals(200, response.statusCode());
			JsonObject metrics = response.bodyAsJsonObject();
			assertNotNull(metrics);
			ctx.completeNow();
		})).onFailure(ctx::failNow);
	}
	
}
