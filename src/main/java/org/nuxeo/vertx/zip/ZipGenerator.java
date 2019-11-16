package org.nuxeo.vertx.zip;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.ReadStream;

/**
 * This class is a {@link ReadStream<Buffer>} that can be used to generate a
 * list of file compressed in a ZIP stream.
 *
 * Typical use is:
 *
 * <code>
 * ZipGenerator zip = new ZipGenerator(vertx, engine, size);
 * zip.endHandler(v -> {
 *			response.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip, application/octet-stream")
 *    	            .putHeader("Content-Disposition", "attachment; filename=\"" + packageName + ".zip\"")
 *                  .end();
 *			}).exceptionHandler(v -> {
 *				response.setStatusCode(500)
 *                      .setStatusMessage(v.getMessage())
 *				        .end();
 *			});
 *
 * Pump.pump(zip, response).start();
 * </code>
 *
 * @author dmetzler
 *
 */
public class ZipGenerator implements ReadStream<Buffer> {

    private static final Logger LOG = LoggerFactory.getLogger(ZipGenerator.class);

    /**
     * PAUSED state.
     */
    public static final int STATUS_PAUSED = 0;

    /**
     * ACTIVE state.
     */
    public static final int STATUS_ACTIVE = 1;

    /**
     * CLOSED state.
     */
    public static final int STATUS_CLOSED = 2;

    /**
     * The current state.
     */
    private volatile int state = STATUS_ACTIVE;

    // Size of the chunk we are sending / reading
    private static final int CHUNK_SIZE = 8092;

    private Vertx vertx;

    // Context in which we are executing
    private Context context;

    // Stream where we write the zip entries
    private ZipOutputStream zos;

    // Stream from where we read compressed data
    private PipedInputStream pis;

    // Intermediate stream to read each fileEntry
    private InputStream fileEntryIS;

    // List of ReadStream Handlers
    private Handler<Throwable> failureHandler;

    private Handler<Buffer> dataHandler;

    private Handler<Void> closeHandler;

    // The source of files
    private FileEntryIterator source;

    /**
     * Creates a generator of ZIP files.
     *
     * @param vertx  a vertx instance
     * @param engine the templating engine
     * @param size   the number of file to include in the ZIP
     * @throws IOException
     */
    public ZipGenerator(Vertx vertx, FileEntryIterator source) throws IOException {

        this.vertx = vertx;
        this.source = source;

        PipedOutputStream out = new PipedOutputStream();
        zos = new ZipOutputStream(out);
        pis = new PipedInputStream(out, CHUNK_SIZE);

    }

    /**
     * Entry point to start the reading process.
     */
    private void doRead() {
        acquireContext();

        if (state == STATUS_ACTIVE) {
            vertx.executeBlocking(promise -> {
                // Flushing the pipe has to happen regulary to to
                // block it
                next(this::doFlushPipe);

                // We start by reading the first file
                next(this::doReadFile);
                promise.complete();
            }, noop());
        }
    }

    /**
     * Reads the next file if available and pass it to the doReadBuffer at next
     * tick. If no more file is available, we close and stop the generator at next
     * tick.
     */
    private void doReadFile() {
        if (hasNextFile()) {

            readFile(source.next(), ar -> {
                if (ar.succeeded()) {
                    next(this::doReadFile);
                } else {
                    handleError(ar.cause());
                }
            });
        } else {
            next(this::doCloseAndStop);
        }
    }

    private void doCloseAndStop() {
        vertx.executeBlocking(v -> {
            try {
                zos.close();
                v.complete();
            } catch (IOException e) {
                v.fail(e);
            }
        }, v -> {
            if (v.succeeded()) {
                doCloseGenerator(closeHandler);
            } else {
                handleError(v.cause());
            }
        });
    }

    private void readFile(FileEntry entry, Handler<AsyncResult<Void>> handler) {
        vertx.executeBlocking(promise -> {
            try {
                // Open the inputstream if needed
                if (fileEntryIS == null) {
                    openStreamForEntry(entry, handler);
                } else {
                    readStreamForEntry(entry, handler);
                }
            } catch (IOException e) {
                handler.handle(Future.failedFuture(e));
            }
        }, noop());
    }

    /**
     * Read the InputStream recursively and notifies the handler when finished
     * success.
     *
     * @param entry   The entry to read
     * @param handler
     * @throws IOException
     */
    private void readStreamForEntry(FileEntry entry, Handler<AsyncResult<Void>> handler) throws IOException {
        // Read a chunk and push to ZIP stream
        int bytesRead = 0;
        int c = 0;

        Buffer buf = Buffer.buffer();
        while ((c = fileEntryIS.read()) != -1 && bytesRead < CHUNK_SIZE) {
            buf.appendInt(c);
            bytesRead++;
        }
        // Here it can block because the pipe may be full (hence wrapping in
        // executeBlocking)
        zos.write(buf.getBytes());

        if (bytesRead == CHUNK_SIZE) {
            // We are not finished so recurse
            vertx.runOnContext(v -> readFile(entry, handler));
        } else {
            // Handle end of file read
            fileEntryIS.close();
            fileEntryIS = null;
            handler.handle(Future.succeededFuture());
        }
    }

    /**
     * Open an InputStream for the given file entry and call
     * {@link ZipGenerator#readFile(FileEntry, Handler)} on Success.
     *
     * @param entry   the file entry
     * @param handler the handler that is passed to readFile
     * @throws IOException
     */
    private void openStreamForEntry(FileEntry entry, Handler<AsyncResult<Void>> handler) throws IOException {
        ZipEntry e = new ZipEntry(entry.getPath());
        zos.putNextEntry(e);
        entry.open(ar -> {
            if (ar.succeeded()) {
                fileEntryIS = ar.result();
                vertx.runOnContext(v -> readFile(entry, handler));
            } else {
                handleError(ar.cause());
            }
        });
    }

    private boolean hasNextFile() {
        return source.hasNext();
    }

    private void doFlushPipe() {
        acquireContext();
        if (this.state == STATUS_ACTIVE) {
            try {
                // Read all possible data from the pipe
                byte[] tmp = new byte[pis.available()];
                int readBytes = pis.read(tmp);
                if (readBytes > 0) {
                    byte[] buffer = new byte[readBytes];
                    System.arraycopy(tmp, 0, buffer, 0, readBytes);
                    dataHandler.handle(Buffer.buffer(buffer));
                }
                next(this::doFlushPipe);

            } catch (IOException e) {
                handleError(e);
            }
        }

    }

    private void next(Runnable f) {
        context.runOnContext(v -> f.run());
    }

    private void acquireContext() {
        if (context == null) {
            context = vertx.getOrCreateContext();
        }
    }

    private <T> void doCloseGenerator(final Handler<T> handler) {
        doCloseGenerator(handler, null);
    }

    private <T> void doCloseGenerator(final Handler<T> handler, T e) {
        state = STATUS_CLOSED;
        context.runOnContext(event -> {
            if (handler != null) {
                handler.handle(e);
            }
        });
    }

    private void handleError(Throwable cause) {
        state = STATUS_CLOSED;
        if (failureHandler != null) {
            LOG.error(cause);
            failureHandler.handle(cause);
        } else {
            LOG.warn("No handler for error: " + cause);
        }
    }

    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        this.dataHandler = handler;
        doRead();
        return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
        return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        this.closeHandler = endHandler;
        return this;
    }

    /**
     * Pauses the reading.
     *
     * @return the current {@code AsyncInputStream}
     */
    @Override
    public ZipGenerator pause() {
        if (state == STATUS_ACTIVE) {
            state = STATUS_PAUSED;
        }
        return this;
    }

    /**
     * Resumes the reading.
     *
     * @return the current {@code AsyncInputStream}
     */
    @Override
    public ZipGenerator resume() {
        switch (state) {
        case STATUS_CLOSED:
            throw new IllegalStateException("Cannot resume, already closed");
        case STATUS_PAUSED:
            state = STATUS_ACTIVE;
            doRead();
        default:
        }
        return this;
    }

    /**
     * Sets the failure handler.
     *
     * @param handler the failure handler.
     * @return the current {@link org.wisdom.framework.vertx.AsyncInputStream}
     */
    @Override
    public ZipGenerator exceptionHandler(Handler<Throwable> handler) {
        this.failureHandler = handler;
        return this;
    }

    private Handler<AsyncResult<Object>> noop() {
        return v -> {
        };
    }

}
