package io.tatum.gateway;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;

public class ProxyServer {

	public static void main(String[] args) {
		Vertx vertx = Vertx.vertx();

		ConfigRetriever retriever = ConfigRetriever.create(vertx,
				new ConfigRetrieverOptions().addStore(new ConfigStoreOptions().setType("env")));

		retriever.getConfig(ar -> {
			if (ar.succeeded()) {
				vertx.deployVerticle(new MainVerticle(), new DeploymentOptions().setConfig(ar.result()));
			} else {
				System.err.println("Failed to retrieve config: " + ar.cause());
			}
		});
	}

}
