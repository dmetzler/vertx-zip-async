package org.nuxeo.vertx.zip;

import java.io.IOException;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;

public class ZipBuilderImpl implements ZipBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ZipBuilderImpl.class);

    private static final int DEFAULT_SIZE = 500;

    private final FreeMarkerTemplateEngine engine;

    private Vertx vertx;

    public ZipBuilderImpl(Vertx vertx) {
        this.vertx = vertx;
        engine = FreeMarkerTemplateEngine.create(vertx);
    }

    @Override
    public void handle(RoutingContext event) {

        HttpServerRequest request = event.request();
        String packageName = request.getParam("name");
        int size = getSizeFromRequest(request);

        HttpServerResponse response = event.response();

        response.setChunked(true);

        try {

            // Generates random file entries
            FileEntryIterator fei = new RandomFileIterator(size, engine);

            ZipGenerator zip = new ZipGenerator(vertx, fei);
            zip.endHandler(v -> {
                response.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip, application/octet-stream")
                        .putHeader("Content-Disposition", "attachment; filename=\"" + packageName + ".zip\"");
                response.end();
            }).exceptionHandler(v -> {
                LOG.error(v);
                response.setStatusCode(500).setStatusMessage(v.getMessage());
                response.end();
            });

            Pump.pump(zip, response).start();

        } catch (IOException e) {
            LOG.error(e);
            response.setStatusCode(500).setStatusMessage(e.getMessage());
            response.end();
        }
    }

    private int getSizeFromRequest(HttpServerRequest request) {
        String sizeStr = request.getParam("size");

        int size = DEFAULT_SIZE;
        try {
            size = Integer.parseInt(sizeStr);
        } catch (NumberFormatException e) {
            LOG.warn("Wrong format given for size, using default ({})", DEFAULT_SIZE);
        }
        return size;
    }

}
