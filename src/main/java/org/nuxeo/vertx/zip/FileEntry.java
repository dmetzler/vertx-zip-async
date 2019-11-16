package org.nuxeo.vertx.zip;

import java.io.InputStream;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;

public interface FileEntry {

    String getPath();

    void open(Handler<AsyncResult<InputStream>> handler);

}
