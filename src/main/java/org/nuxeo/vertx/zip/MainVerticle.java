package org.nuxeo.vertx.zip;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;

public class MainVerticle extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(MainVerticle.class);

    public static void main(String... args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(MainVerticle.class, new DeploymentOptions());
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {

        LOG.info(String.format("Starting [%s]", this.getClass().getName()));

        Integer port = config().getInteger("port", 8080);
        Router router = Router.router(vertx);

        // Configure HealthCheck
        HealthChecks hc = HealthChecks.create(vertx);
        hc.register("ping", future -> future.complete(Status.OK()));
        router.get("/healthz").handler(HealthCheckHandler.createWithHealthChecks(hc));

        // Configure our ZIP handler
        ZipBuilder zipBuilder = ZipBuilder.create(vertx);
        router.get("/zip/:name.zip").handler(zipBuilder::handle);

        // Start the server
        vertx.createHttpServer().requestHandler(router).listen(port, http -> {
            if (http.succeeded()) {
                startPromise.complete();
                LOG.info(String.format("HTTP server started on port %d", port));
            } else {
                startPromise.fail(http.cause());
            }
        });
    }

}