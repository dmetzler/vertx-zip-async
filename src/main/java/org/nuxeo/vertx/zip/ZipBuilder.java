package org.nuxeo.vertx.zip;

import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

public interface ZipBuilder {

    static ZipBuilder create(Vertx vertx) {
        return new ZipBuilderImpl(vertx);
    }

    public void handle(RoutingContext event);


}
