package org.nuxeo.vertx.zip;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;

import com.thedeanda.lorem.Lorem;
import com.thedeanda.lorem.LoremIpsum;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.common.template.TemplateEngine;

public class RandomFileIterator implements FileEntryIterator {

    private static final Lorem LOREM = LoremIpsum.getInstance();

    private final TemplateEngine engine;
    private final int size;

    private int index = 0;

    public RandomFileIterator(int size, TemplateEngine engine) {
        this.size = size;
        this.engine = engine;
    }

    @Override
    public boolean hasNext() {
        return index < size;
    }

    @Override
    public FileEntry next() {
        return new TemplatedFileEntry(index++, engine);
    }

    public static class TemplatedFileEntry implements FileEntry {

        private int index;
        private TemplateEngine engine;

        public TemplatedFileEntry(int index, TemplateEngine engine) {
            this.index = index;
            this.engine = engine;
        }

        @Override
        public String getPath() {
            return String.format("file_%03d.txt", index);
        }

        @Override
        public void open(Handler<AsyncResult<InputStream>> handler) {
            JsonObject renderingContext = new JsonObject() //
                    .put("Title", "Title " + index)//
                    .put("Author", LOREM.getName())//
                    .put("Date", LocalDateTime.now().toString())//
                    .put("Body", LOREM.getHtmlParagraphs(1, 2));

            engine.render(renderingContext, "template.ftl", rendered -> {
                if (rendered.failed()) {
                    handler.handle(Future.failedFuture(rendered.cause()));
                } else {
                    Buffer buf = rendered.result();
                    InputStream is = new ByteArrayInputStream(buf.getBytes());
                    handler.handle(Future.succeededFuture(is));
                }
            });
        }

    }

}
