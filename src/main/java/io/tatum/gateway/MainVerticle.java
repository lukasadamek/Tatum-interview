package io.tatum.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;


/**
 * MainVerticle is the entry point of the Ethereum JSON-RPC proxy server built on Vert.x.
 * It configures an HTTPS server that forwards JSON-RPC requests to a specified Ethereum node,
 * tracks method usage metrics, and exposes a monitoring endpoint.
 *
 * <p>
 * Configuration parameters may be supplied via Vert.x DeploymentOptions or environment variables:
 * <ul>
 *   <li>{@code PROXY_PORT} – Port on which the proxy server will listen (default: 8443)</li>
 *   <li>{@code ETHEREUM_NODE_URL} – URL of the target Ethereum node (default: https://ethereum.publicnode.com)</li>
 *   <li>{@code KEYSTORE_PATH} – Path to the TLS keystore file (default: keystore.jks)</li>
 *   <li>{@code KEYSTORE_PASSWORD} – Password for the keystore (default: secretpassword)</li>
 * </ul>
 * </p>
 *
 * <p>
 * The server exposes two endpoints:
 * <ul>
 *   <li>{@code /rpc} – Accepts JSON-RPC payloads and forwards them to the Ethereum node</li>
 *   <li>{@code /metrics} – Returns a JSON object with counts of method calls</li>
 * </ul>
 * </p>
 *
 * @author Lukas Adamek
 */
public class MainVerticle extends AbstractVerticle {

	private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

	public static final String PARAM_PROXY_PORT = "PROXY_PORT";
	public static final String PARAM_ETHEREUM_NODE_URL = "ETHEREUM_NODE_URL";
	public static final String PARAM_KEYSTORE_PASSWORD = "KEYSTORE_PASSWORD";
	public static final String PARAM_KEYSTORE_PATH = "KEYSTORE_PATH";

	private static final String METHOD_RPC = "/rpc";
    private static final String METHOD_METRICS = "/metrics";
	
    private String ethereumNodeUrl;
    private int proxyPort;
    private String keystorePath;
    private String keystorePassword;
    
    private WebClient webClient;
    
    // A thread-safe map to store the counts of each JSON-RPC method call.
    private final ConcurrentHashMap<String, AtomicInteger> methodCalls = new ConcurrentHashMap<>();

    @Override
    public void start(Promise<Void> startPromise) {
   
    	JsonObject cfg = config();
        ethereumNodeUrl = cfg.getString(PARAM_ETHEREUM_NODE_URL, "https://ethereum.publicnode.com");
        proxyPort = cfg.getInteger(PARAM_PROXY_PORT, 8443);
        keystorePath = cfg.getString(PARAM_KEYSTORE_PATH, "keystore.jks");
        keystorePassword = cfg.getString(PARAM_KEYSTORE_PASSWORD, "secretpassword");
		
        webClient = WebClient.create(vertx, new WebClientOptions()
                .setKeepAlive(true)
                .setConnectTimeout(5000)
                .setMaxPoolSize(20));

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post(METHOD_RPC).handler(this::handleRpc);
        router.get(METHOD_METRICS).handler(this::handleMetrics);

        startHttpsServer(startPromise, router);
    }

	private void startHttpsServer(Promise<Void> startPromise, Router router) {
		HttpServerOptions options = createTLSConfig();
		
		vertx.createHttpServer(options)
			.requestHandler(router)
			.listen(https -> {
				if (https.succeeded()) {
					LOGGER.info("HTTPS Proxy server started on port: {}, Ethereum node url: {}, keystorePath: {}", proxyPort, ethereumNodeUrl, keystorePath, keystorePassword);
					startPromise.complete();
				} else {
					LOGGER.error("Failed to start HTTPS Proxy server", https.cause());
					startPromise.fail(https.cause());
				}
			});
	}
    
	/**
	 * Configure TLS using a Java KeyStore (JKS)
	 */
	private HttpServerOptions createTLSConfig() {
        JksOptions jksOptions = new JksOptions()
             .setPath(keystorePath)
             .setPassword(keystorePassword);
        
		HttpServerOptions options = new HttpServerOptions()
			.setPort(proxyPort)
			.setSsl(true)
			.setKeyCertOptions(jksOptions);

		return options;
	}

    private void handleRpc(RoutingContext ctx) {
        parseJsonBody(ctx)
            .compose(body -> {
                trackMethods(body, ctx);
                return forwardRequest(body);
            })
	        .onSuccess(response -> {
	            // Send the response from the ethereumNodeUrl back to the client.
	        	ctx.response()
	                .putHeader("Content-Type", "application/json")
	                .end(response.bodyAsString());
	        })
            .onFailure(err -> {
                LOGGER.error("Request to Ethereum node failed", err);
                sendError(ctx, "Proxy error", "Could not connect to Ethereum node.");
            });
    }

    private Future<Object> parseJsonBody(RoutingContext ctx) {
        Promise<Object> promise = Promise.promise();

        try {
            Object parsed = Json.decodeValue(ctx.body().asString());
            if (parsed instanceof JsonObject || parsed instanceof JsonArray) {
                promise.complete(parsed);
            } else {
                sendError(ctx, "Parse error", "Invalid JSON received.");
                promise.fail("Invalid format");
            }
        } catch (Exception e) {
            LOGGER.error("JSON parse failed", e);
            sendError(ctx, "Parse error", "Could not parse JSON.");
            promise.fail(e);
        }

        return promise.future();
    }

    private void trackMethods(Object body, RoutingContext ctx) {
        if (body instanceof JsonObject) {
            trackMethodCall((JsonObject) body, ctx);
            
        } else if (body instanceof JsonArray) {
            ((JsonArray) body).forEach(entry -> {
                if (entry instanceof JsonObject) {
                    trackMethodCall((JsonObject) entry, ctx);
                }
            });
        }
    }

    private void trackMethodCall(JsonObject request, RoutingContext ctx) {
        String method = request.getString("method");
        if (method == null || method.isEmpty()) {
            sendError(ctx, "Invalid request", "Missing method field.");
            return;
        }
        // Log the access information
        LOGGER.info("From {}: method={}, id={}", getClientIP(ctx), method, request.getValue("id"));
        	
        methodCalls.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet();
    }

    private Future<HttpResponse<Buffer>> forwardRequest(Object body) {
        return webClient.postAbs(ethereumNodeUrl)
                        .sendJson(body);
    }

    private void handleMetrics(RoutingContext ctx) {
        JsonObject metrics = new JsonObject();
        methodCalls.forEach((method, count) -> metrics.put(method, count.get()));
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(metrics.encodePrettily());
    }

    private void sendError(RoutingContext ctx, String message, String detail) {
        JsonObject error = new JsonObject()
            .put("jsonrpc", "2.0")
            .put("error", new JsonObject()
                .put("message", message)
                .put("data", detail));
        ctx.response()
            .setStatusCode(400)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }

    private String getClientIP(RoutingContext ctx) {
        return ctx.request().remoteAddress().host();
    }
}
