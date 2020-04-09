/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.internal.coverage.j2d;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collector;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.awt.image.WritableRenderedImage;
import java.awt.image.ImagingOpException;
import org.apache.sis.util.Classes;
import org.apache.sis.util.Exceptions;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.internal.feature.Resources;
import org.apache.sis.internal.system.CommonExecutor;
import org.apache.sis.internal.util.Strings;


/**
 * A read or write action to execute on each tile of an image. The operation may be executed
 * in a single thread or can be multi-threaded (each tile processed fully in a single thread).
 * If the operation is to be executed in a single thread, or if the subclass is concurrent
 * (it usually means that it does not hold any mutable state), then subclasses can override
 * and invoke the methods in one of the following rows:
 *
 * <table>
 *   <caption>Methods to use in single-thread or with concurrent implementations</caption>
 *   <tr>
 *     <th>Override</th>
 *     <th>Then invoke (single thread)</th>
 *     <th>Or invoke (multi-thread)</th>
 *   </tr><tr>
 *     <td>{@link #readFrom(Raster)}</td>
 *     <td>{@link #readFrom(RenderedImage)}</td>
 *     <td>{@link #parallelReadFrom(RenderedImage)}</td>
 *   </tr><tr>
 *     <td>{@link #writeTo(WritableRaster)}</td>
 *     <td>{@link #writeTo(WritableRenderedImage)}</td>
 *     <td>{@link #parallelWriteTo(WritableRenderedImage)}</td>
 *   </tr>
 * </table>
 *
 * <p>If the operation should be multi-threaded and produce a result, then invoke
 * {@link #executeOnReadable executeOnReadable(…)} or {@link #executeOnWritable executeOnWritable(…)}
 * method. Those methods are inspired from {@link java.util.stream.Stream#collect(Collector)} API.</p>
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 * @since   1.1
 * @module
 */
public class TileOpExecutor {
    /**
     * Minimum/maximum index of tiles to process, inclusive.
     */
    private final int minTileX, minTileY, maxTileX, maxTileY;

    /**
     * Creates a new operation for tiles in the specified region of the specified image.
     * It is caller responsibility to ensure that {@code aoi} is contained inside {@code image} bounds.
     *
     * @param  image  the image from which tiles will be fetched.
     * @param  aoi    region of interest, or {@code null} for the whole image.
     * @throws ArithmeticException if some tile indices are too large.
     */
    public TileOpExecutor(final RenderedImage image, final Rectangle aoi) {
        if (aoi != null) {
            final int  tileWidth       = image.getTileWidth();
            final int  tileHeight      = image.getTileHeight();
            final long tileGridXOffset = image.getTileGridXOffset();   // We want 64 bits arithmetic in operations below.
            final long tileGridYOffset = image.getTileGridYOffset();
            minTileX = Math.toIntExact(Math.floorDiv(aoi.x                     - tileGridXOffset, tileWidth ));
            minTileY = Math.toIntExact(Math.floorDiv(aoi.y                     - tileGridYOffset, tileHeight));
            maxTileX = Math.toIntExact(Math.floorDiv(aoi.x + (aoi.width  - 1L) - tileGridXOffset, tileWidth ));
            maxTileY = Math.toIntExact(Math.floorDiv(aoi.y + (aoi.height - 1L) - tileGridYOffset, tileHeight));
        } else {
            minTileX = image.getMinTileX();
            minTileY = image.getMinTileY();
            maxTileX = Math.addExact(minTileX, image.getNumXTiles() - 1);
            maxTileY = Math.addExact(minTileY, image.getNumYTiles() - 1);
        }
    }

    /**
     * Returns {@code true} if the region of interest covers at least two tiles.
     * Returns {@code false} if the region of interest covers a single tile or no tile at all.
     *
     * @return whether the operation will be executed on two tiles or more.
     */
    public final boolean isMultiTiled() {
        /*
         * Following expression is negative if at least one (max - min) value is negative
         * (empty case), and 0 if all (max - min) values are zero (singleton case).
         */
        return ((maxTileX - minTileX) | (maxTileY - minTileY)) > 0;
    }

    /**
     * Returns the range of indices of tiles to be processed by this {@code TileOpExecutor}.
     *
     * @return range of tile indices to be processed.
     */
    public final Rectangle getTileIndices() {
        return new Rectangle(minTileX, minTileY, maxTileX - minTileX + 1, maxTileY - minTileY + 1);
    }

    /**
     * Executes the read operation on the given tile.
     * The default implementation does nothing.
     * This method should be overridden if the user intends to call {@link #readFrom(RenderedImage)} for execution
     * in a single thread, or {@link #parallelReadFrom(RenderedImage)} for multi-threaded execution. In the single
     * thread case, it is okay for this method to modify some mutable states in the subclass. In the multi-thread
     * case, the subclass implementation shall be immutable or concurrent.
     *
     * @param  source  the tile to read.
     * @throws Exception if an error occurred while processing the tile.
     */
    protected void readFrom(Raster source) throws Exception {
    }

    /**
     * Executes the write operation on the given tile.
     * The default implementation does nothing.
     * This method should be overridden if the user intends to call {@link #writeTo(WritableRenderedImage)} for
     * execution in a single thread, or {@link #parallelWriteTo(WritableRenderedImage)} for multi-threaded execution.
     * In the single thread case, it is okay for this method to modify some mutable states in the subclass.
     * In the multi-thread case, the subclass implementation shall be immutable or concurrent.
     *
     * @param  target  the tile where to write.
     * @throws Exception if an error occurred while computing the values to write.
     */
    protected void writeTo(WritableRaster target) throws Exception {
    }

    /**
     * Executes the read action sequentially on tiles of the specified source image.
     * The given source should be the same than the image specified at construction time.
     * Only tiles intersecting the area of interest will be processed.
     * For each tile, the {@link #readFrom(Raster)} method will be invoked in current thread.
     *
     * <p>If a tile processing throws an exception, then this method stops immediately;
     * remaining tiles are not processed. This policy is suited to the cases where the
     * caller will not return any result in case of error.</p>
     *
     * <p>This method does not parallelize tile operations, because it is invoked
     * in contexts where it should apply on exactly one tile most of the times.</p>
     *
     * @param  source  the image to read. This is usually the image specified at construction time,
     *         but other images are okay if they share the same pixel and tile coordinate systems.
     * @throws ImagingOpException if an exception occurred during {@link RenderedImage#getTile(int, int)}
     *         or {@link #readFrom(Raster)} execution. This exception wraps the original exception as its
     *         {@linkplain ImagingOpException#getCause() cause}.
     */
    public final void readFrom(final RenderedImage source) {
        for (int ty = minTileY; ty <= maxTileY; ty++) {
            for (int tx = minTileX; tx <= maxTileX; tx++) try {
                readFrom(source.getTile(tx, ty));
            } catch (Exception ex) {
                Throwable e = trimImagingWrapper(ex);
                if (!(e instanceof ImagingOpException)) {
                    e = new ImagingOpException(Resources.format(Resources.Keys.CanNotProcessTile_2, tx, ty)).initCause(ex);
                }
                throw (ImagingOpException) e;
            }
        }
    }

    /**
     * Executes the write action sequentially on tiles of the specified target image.
     * The given target should be the same than the image specified at construction time.
     * Only tiles intersecting the area of interest will be processed.
     * For each tile, the {@link #writeTo(WritableRaster)} method will be invoked in current thread.
     *
     * <p>If a tile processing throws an exception, then this method continues processing other tiles
     * and will throw the wrapper exception only after all tiles have been processed. This policy is
     * suited to the cases where the target image will continue to exist after this method call and
     * we want to have a relatively consistent state.</p>
     *
     * <p>This method does not parallelize tile operations, because it is invoked
     * in contexts where it should apply on exactly one tile most of the times.</p>
     *
     * @param  target  the image where to write. This is usually the image specified at construction time,
     *         but other images are okay if they share the same pixel and tile coordinate systems.
     * @throws ImagingOpException if an exception occurred during {@link WritableRenderedImage#getWritableTile(int, int)},
     *         {@link #writeTo(WritableRaster)} or {@link WritableRenderedImage#releaseWritableTile(int, int)} execution.
     *         This exception wraps the original exception as its {@linkplain ImagingOpException#getCause() cause}.
     */
    public final void writeTo(final WritableRenderedImage target) {
        ImagingOpException error = null;
        for (int ty = minTileY; ty <= maxTileY; ty++) {
            for (int tx = minTileX; tx <= maxTileX; tx++) try {
                final WritableRaster tile = target.getWritableTile(tx, ty);
                try {
                    writeTo(tile);
                } finally {
                    target.releaseWritableTile(tx, ty);
                }
            } catch (Exception ex) {
                final Throwable e = trimImagingWrapper(ex);
                if (error != null) {
                    error.addSuppressed(e);
                } else if (e instanceof ImagingOpException) {
                    error = (ImagingOpException) e;
                } else {
                    error = new ImagingOpException(Resources.format(Resources.Keys.CanNotUpdateTile_2, tx, ty));
                    error.initCause(e);
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * Executes the read action in parallel on tiles of the specified source image.
     * The given source should be the same than the image specified at construction time.
     * Only tiles intersecting the area of interest will be processed.
     * For each tile, the {@link #readFrom(Raster)} method will be invoked
     * in an arbitrary thread (may be the current one).
     *
     * <h4>Errors management</h4>
     * If a tile processing throws an exception, then the other threads will finish computing
     * their current tile but no other tiles will be fetched; remaining tiles are not processed.
     * This policy is suited to the cases where the caller will not return any result in case of error.
     *
     * <h4>Concurrency requirements</h4>
     * Subclasses must override {@link #readFrom(Raster)} with a concurrent implementation.
     * The {@link RenderedImage#getTile(int, int)} implementation of the given image must also
     * support concurrency.
     *
     * @param  source  the image to read. This is usually the image specified at construction time,
     *         but other images are okay if they share the same pixel and tile coordinate systems.
     * @throws ImagingOpException if an exception occurred during {@link RenderedImage#getTile(int, int)}
     *         or {@link #readFrom(Raster)} execution. This exception wraps the original exception as its
     *         {@linkplain ImagingOpException#getCause() cause}.
     */
    public final void parallelReadFrom(final RenderedImage source) {
        if (isMultiTiled()) {
            executeOnReadable(source, executor((ignore,tile) -> {
                try {
                    readFrom(tile);
                } catch (Exception ex) {
                    throw Worker.rethrowOrWrap(ex);             // Will be caught again by Worker.run().
                }
            }), null);
        } else {
            readFrom(source);
        }
    }

    /**
     * Executes the write action in parallel on tiles of the specified target image.
     * The given target should be the same than the image specified at construction time.
     * Only tiles intersecting the area of interest will be processed.
     * For each tile, the {@link #writeTo(WritableRaster)} method will be invoked
     * in an arbitrary thread (may be the current one).
     *
     * <h4>Errors management</h4>
     * If a tile processing throws an exception, then this method continues processing other tiles
     * and will throw the wrapper exception only after all tiles have been processed. This policy is
     * suited to the cases where the target image will continue to exist after this method call and
     * we want to have a relatively consistent state.
     *
     * <h4>Concurrency requirements</h4>
     * Subclasses must override {@link #writeTo(WritableRaster)} with a concurrent implementation.
     * The {@link WritableRenderedImage#getWritableTile(int, int)} and
     * {@link WritableRenderedImage#releaseWritableTile(int, int)} implementations
     * of the given image must also support concurrency.
     *
     * @param  target  the image where to write. This is usually the image specified at construction time,
     *         but other images are okay if they share the same pixel and tile coordinate systems.
     * @throws ImagingOpException if an exception occurred during {@link WritableRenderedImage#getWritableTile(int, int)},
     *         {@link #writeTo(WritableRaster)} or {@link WritableRenderedImage#releaseWritableTile(int, int)} execution.
     *         This exception wraps the original exception as its {@linkplain ImagingOpException#getCause() cause}.
     */
    public final void parallelWriteTo(final WritableRenderedImage target) {
        if (isMultiTiled()) {
            executeOnWritable(target, executor((ignore,tile) -> {
                try {
                    writeTo(tile);
                } catch (Exception ex) {
                    throw Worker.rethrowOrWrap(ex);             // Will be caught again by Worker.run().
                }
            }), null);
        } else {
            writeTo(target);
        }
    }

    /**
     * Returns a collector to be used only as an executor: the accumulator is null and the combiner does nothing.
     *
     * @param  <RT>    either {@link Raster} or {@link WritableRaster}.
     * @param  action  the action to execute on each tile.
     * @return a collector which will merely act as an executor for the given action.
     */
    private static <RT extends Raster> Collector<RT,Void,Void> executor(final BiConsumer<Void,RT> action) {
        return Collector.<RT,Void>of(() -> null, action, (old,ignore) -> old);
    }

    /**
     * Executes a specified read action in parallel on all tiles of the specified image.
     * The action is specified by 3 or 4 properties of the given {@code collector}:
     *
     * <ul class="verbose">
     *   <li>
     *     {@link Collector#supplier()} creates a new instance of type <var>A</var> for each thread
     *     (those instances may be {@code null} if such objects are not needed).
     *     That object does not need to be thread-safe since each instance will be used by only one thread.
     *     Note however that the thread may use that object for processing any number of {@link Raster} tiles,
     *     including zero.
     *   </li><li>
     *     {@link Collector#accumulator()} provides the consumer to execute on each tile. That consumer will
     *     receive two arguments: the above-cited supplied instance of <var>A</var> (unique to each thread,
     *     may be {@code null}), and the {@link Raster} instance to process. That consumer returns no value;
     *     instead the supplied instance of <var>A</var> should be modified in-place if desired.
     *   </li><li>
     *     {@link Collector#combiner()} provides a function which, given two instances of <var>A</var>
     *     computed by two different threads, combines them in a single instance of <var>A</var>.
     *     This combiner will be invoked after a thread finished to process all its {@link Raster} tiles,
     *     and only if the two objects to combine are not null. This combiner does not need to be thread-safe.
     *   </li><li>
     *     {@link Collector#finisher()} is invoked exactly once in current thread after the processing of all tiles
     *     have been completed in all threads. This function converts the final value of <var>A</var> into the type
     *     <var>R</var> to be returned. It is often an identity function.
     *   </li>
     * </ul>
     *
     * <h4>Errors management</h4>
     * If an error occurred during the processing of a tile, then there is a choice:
     *
     * <ul class="verbose">
     *   <li>
     *     If the {@code errorHandler} is {@code null}, then all threads will finish the tiles they were
     *     processing at the time the error occurred, but will not take any other tile (i.e. remaining tiles
     *     will be left unprocessed). The exception that occurred is wrapped in an {@link ImagingOpException}
     *     and thrown.
     *   </li><li>
     *     If the {@code errorHandler} is non-null, then the exception is wrapped in a {@link LogRecord} and
     *     the processing continues with other tiles. If more exceptions happen, those subsequent exceptions
     *     will be added to the first one by {@link Exception#addSuppressed(Throwable)}.
     *     After all tiles have been processed, the error handler will be invoked with that {@link LogRecord}.
     *     That {@code LogRecord} has the level, message and exception properties set, but not the source class name,
     *     source method name or logger name. The error handler should set those properties itself.
     *   </li>
     * </ul>
     *
     * <h4>Concurrency requirements</h4>
     * The {@link RenderedImage#getTile(int, int)} implementation of the given image must support concurrency.
     *
     * @param  <A>           the type of the thread-local object to be given to each thread.
     * @param  <R>           the type of the final result. This is often the same as <var>A</var>.
     * @param  source        the image to read. This is usually the image specified at construction time,
     *                       but other images are okay if they share the same pixel and tile coordinate systems.
     * @param  collector     the action to execute on each {@link Raster}, together with supplier and combiner
     *                       of thread-local objects of type <var>A</var>. See above javadoc for more information.
     * @param  errorHandler  where to report exceptions, or {@code null} for throwing them.
     * @return the final result computed by finisher (may be {@code null}).
     * @throws ImagingOpException if an exception occurred during {@link RenderedImage#getTile(int, int)}
     *         or {@link #readFrom(Raster)} execution, and {@code errorHandler} is {@code null}.
     * @throws RuntimeException if an exception occurred elsewhere (for example in the combiner or finisher).
     */
    public final <A,R> R executeOnReadable(final RenderedImage source,
                                           final Collector<? super Raster, A, R> collector,
                                           final Consumer<LogRecord> errorHandler)
    {
        ArgumentChecks.ensureNonNull("source", source);
        ArgumentChecks.ensureNonNull("collector", collector);
        return ReadWork.execute(this, source, collector, errorHandler);
    }

    /**
     * Executes a specified write action in parallel on all tiles of the specified image.
     * The action is specified by 3 or 4 properties of the given {@code collector}:
     *
     * <ul class="verbose">
     *   <li>
     *     {@link Collector#supplier()} creates a new instance of type <var>A</var> for each thread
     *     (those instances may be {@code null} if such objects are not needed).
     *     That object does not need to be thread-safe since each instance will be used by only one thread.
     *     Note however that the thread may use that object for processing any number of {@link WritableRaster}
     *     tiles, including zero.
     *   </li><li>
     *     {@link Collector#accumulator()} provides the consumer to execute on each tile. That consumer will
     *     receive two arguments: the above-cited supplied instance of <var>A</var> (unique to each thread,
     *     may be {@code null}), and the {@link WritableRaster} instance to process. That consumer returns
     *     no value; instead the supplied instance of <var>A</var> should be modified in-place if desired.
     *   </li><li>
     *     {@link Collector#combiner()} provides a function which, given two instances of <var>A</var>
     *     computed by two different threads, combines them in a single instance of <var>A</var>.
     *     This combiner will be invoked after a thread finished to process all its {@link WritableRaster} tiles,
     *     and only if the two objects to combine are not null. This combiner does not need to be thread-safe.
     *   </li><li>
     *     {@link Collector#finisher()} is invoked exactly once in current thread after the processing of all tiles
     *     have been completed in all threads. This function converts the final value of <var>A</var> into the type
     *     <var>R</var> to be returned. It is often an identity function.
     *   </li>
     * </ul>
     *
     * <h4>Errors management</h4>
     * If an error occurred during the processing of a tile, the exception is remembered and the processing
     * continues with other tiles. If more exceptions happen, those subsequent exceptions will be added to
     * the first one by {@link Exception#addSuppressed(Throwable)}. After all tiles have been processed,
     * there is a choice:
     *
     * <ul>
     *   <li>If the {@code errorHandler} is {@code null}, the exception is wrapped in an {@link ImagingOpException} and thrown.</li>
     *   <li>If the {@code errorHandler} is non-null, the exception is wrapped in a {@link LogRecord} and given to the handler.
     *       That {@code LogRecord} has the level, message and exception properties set, but not the source class name,
     *       source method name or logger name. The error handler should set those properties itself.</li>
     * </ul>
     *
     * <h4>Concurrency requirements</h4>
     * The {@link WritableRenderedImage#getWritableTile(int, int)} and
     * {@link WritableRenderedImage#releaseWritableTile(int, int)} implementations
     * of the given image must support concurrency.
     *
     * @param  <A>           the type of the thread-local object to be given to each thread.
     * @param  <R>           the type of the final result. This is often the same as <var>A</var>.
     * @param  target        the image where to write. This is usually the image specified at construction time,
     *                       but other images are okay if they share the same pixel and tile coordinate systems.
     * @param  collector     the action to execute on each {@link WritableRaster}, together with supplier and combiner
     *                       of thread-local objects of type <var>A</var>. See above javadoc for more information.
     * @param  errorHandler  where to report exceptions, or {@code null} for throwing them.
     * @return the final result computed by finisher. This is often {@code null} because the purpose of calling
     *         {@code executeOnWritable(…)} is more often to update existing tiles instead than to compute a value.
     * @throws ImagingOpException if an exception occurred during {@link WritableRenderedImage#getWritableTile(int, int)},
     *         {@link #writeTo(WritableRaster)} or {@link WritableRenderedImage#releaseWritableTile(int, int)} execution,
     *         and {@code errorHandler} is {@code null}.
     * @throws RuntimeException if an exception occurred elsewhere (for example in the combiner or finisher).
     */
    public final <A,R> R executeOnWritable(final WritableRenderedImage target,
                                           final Collector<? super WritableRaster,A,R> collector,
                                           final Consumer<LogRecord> errorHandler)
    {
        ArgumentChecks.ensureNonNull("target", target);
        ArgumentChecks.ensureNonNull("collector", collector);
        return WriteWork.execute(this, target, collector, errorHandler);
    }




    /**
     * Tile indices of the next tile to process in a multi-threaded computation. When a computation is splitted
     * between many threads, all workers will share a reference to the same {@link Cursor} instance for fetching
     * the indices of the next tile in iteration order no matter if requested by the same or different threads.
     * We do that on the assumption that if calls to {@link RenderedImage#getTile(int, int)} causes read operations
     * from a file, iteration order corresponds to consecutive tiles in the file and those tiles are loaded more
     * efficiently with sequential read operations.
     *
     * <p>Current implementation uses row major iteration. A future version could use an image property giving the
     * preferred iteration order (possibly as a list or an array of tile indices). When there is no indication about
     * the preferred iteration order, a future version could possible uses Hilbert iterator for processing nearby
     * tiles together (assuming they are more likely to have been computed at a near instant).</p>
     *
     * @param  <RI>  {@link RenderedImage} or {@link WritableRenderedImage}.
     * @param  <A>   type of the thread-local object (the accumulator) for holding intermediate results.
     */
    @SuppressWarnings("serial")                 // Not intended to be serialized.
    private final class Cursor<RI extends RenderedImage, A> extends AtomicInteger {
        /**
         * The image from which to read tiles or where to write tiles.
         * In the later case, must be an instance of {@link WritableRenderedImage}.
         */
        final RI image;

        /**
         * The number of tiles in a row of the area of interest.
         *
         * @see #next(Worker)
         */
        private final int numXTiles;

        /**
         * The operation to execute after a thread finished to process all its tiles,
         * for combining its result with the result of another thread.
         *
         * @see #accumulator
         * @see #accumulate(Object)
         */
        private final BinaryOperator<A> combiner;

        /**
         * The cumulated result of all threads. Every time a thread finishes its work,
         * it calls {@link #accumulate(Object)} for combining its result with previous
         * value that may exist in this field.
         *
         * @see #combiner
         * @see #accumulate(Object)
         */
        private A accumulator;

        /**
         * The errors that occurred while computing a tile, or {@code null} if none.
         *
         * @see #stopOnError
         * @see #recordError(Worker, Throwable)
         */
        private LogRecord errors;

        /**
         * Whether to stop of the first error. If {@code true} the error will be reported as soon as possible.
         * If {@code false}, processing of all tiles will be completed before the error is reported.
         *
         * @see #errors
         * @see #recordError(Worker, Throwable)
         */
        private final boolean stopOnError;

        /**
         * Creates a new cursor initialized to the indices of the first tile.
         *
         * @param image        the image to read or the image where to write.
         * @param collector    provides the combiner of thread-local objects of type <var>A</var>.
         * @param stopOnError  whether to stop of the first error or to process all tiles before to report the error.
         */
        Cursor(final RI image, final Collector<?,A,?> collector, final boolean stopOnError) {
            this.image       = image;
            this.combiner    = collector.combiner();
            this.numXTiles   = Math.incrementExact(Math.subtractExact(maxTileX, minTileX));
            this.stopOnError = stopOnError;
        }

        /**
         * Returns the suggested number of worker threads to create, excluding the current thread.
         * This method always returns a value at least one 1 less than the number of tiles because
         * the current thread will be itself a worker.
         */
        final int getNumWorkers() {
            return Math.max((int) Math.min(CommonExecutor.PARALLELISM, numXTiles * (((long) maxTileY) - minTileY + 1) - 1), 0);
        }

        /**
         * Sets the given worker to the indices of the next tile. This method is invoked by all worker thread
         * before each new tile to process. We return tiles in iteration order, regardless which thread is
         * requesting for next tile, for the reasons documented in {@link Cursor} javadoc.
         *
         * @param  indices  the worker where to update {@link Worker#tx} and {@link Worker#ty} indices.
         * @return {@code true} if the tile at the updated indices should be processed, or {@code false}
         *         if there is no more tile to process.
         */
        final boolean next(final Worker<RI,?,A> indices) {
            final int index = getAndIncrement();
            if (index >= 0) {
                indices.tx = Math.addExact(minTileX, index % numXTiles);
                indices.ty = Math.addExact(minTileY, index / numXTiles);
                return indices.ty <= maxTileY;
            }
            return false;
        }

        /**
         * Invoked when a thread finished to process all its tiles for combining its result with the result
         * of previous threads. This method does nothing if the given result is null.
         *
         * @param  result  the result computed in current thread (may be {@code null}).
         */
        final void accumulate(final A result) {
            if (result != null) {
                synchronized (this) {
                    accumulator = (accumulator == null) ? result : combiner.apply(accumulator, result);
                }
            }
        }

        /**
         * Invoked after the current thread finished to process all its tiles. If some other threads are still
         * computing their tiles, this method waits for those threads to complete (each thread has at most one
         * tile to complete). After all threads completed, this method computes the final result and reports
         * the errors if any.
         *
         * @param  <R>           the final type of the result. This is often the same type than <var>A</var>.
         * @param  workers       handlers of all worker threads other than the current threads.
         *                       Content of this array may be modified by this method.
         * @param  collector     provides the finisher to use for computing final result of type <var>R</var>.
         * @param  errorHandler  where to report exceptions, or {@code null} for throwing them.
         * @return the final result computed by finisher (may be {@code null}).
         * @throws ImagingOpException if an exception occurred during {@link Worker#executeOnCurrentTile()}
         *         and the {@code errorHandler} is {@code null}.
         * @throws RuntimeException if an exception occurred elsewhere (for example in the combiner or finisher).
         */
        final <R> R finish(final Future<?>[] workers, final Collector<?,A,R> collector, final Consumer<LogRecord> errorHandler) {
            /*
             * Before to wait for other threads to complete their work, we need to remove from executor queue all
             * workers that did not yet started their run. Those threads may be waiting for an executor thread to
             * become available, which may never happen if all threads are waiting for a non-running task.
             */
            for (int i=0; i<workers.length; i++) {
                if (CommonExecutor.unschedule(workers[i])) {
                    workers[i] = null;
                }
            }
            for (final Future<?> task : workers) try {
                if (task != null) task.get();
            } catch (ExecutionException ex) {
                /*
                 * This is not an exception that occurred in Worker.executeOnCurrentTile(), RenderedImage.getTile(…)
                 * or similar methods, otherwise it would have been handled by Worker.run(). This is an error or an
                 * exception that occurred elsewhere, for example in the combiner, in which case we do not wrap it
                 * in an ImagingOpException.
                 */
                throw Worker.rethrowOrWrap(ex.getCause());
            } catch (InterruptedException ex) {
                /*
                 * If someone does not want to let us wait, do not wait for other worker threads neither.
                 * We will report that interruption as an error.
                 */
                synchronized (this) {
                    if (errors == null) {
                        errors = new LogRecord(Level.WARNING, ex.toString());
                        errors.setThrown(ex);
                    } else {
                        errors.getThrown().addSuppressed(ex);
                    }
                }
                break;
            }
            /*
             * Computes final result. The synchronization below should not be necessary since all threads
             * finished their work, unless an `InterruptedException` has been caught above in which case
             * it is possible that a few threads are still running.
             */
            final R result;
            synchronized (this) {
                result = collector.finisher().apply(accumulator);
                if (errors != null) {
                    if (errorHandler != null) {
                        errorHandler.accept(errors);
                    } else {
                        final Throwable ex = trimImagingWrapper(errors.getThrown());
                        final String message = new SimpleFormatter().formatMessage(errors);
                        throw (ImagingOpException) new ImagingOpException(message).initCause(ex);
                    }
                }
            }
            return result;
        }

        /**
         * Stores the given exception in a log record. We use a log record in order to initialize
         * the timestamp and thread ID to the values they had at the time the first error occurred.
         *
         * @param  indices  the worker thread where the exception occurred. Its {@link Worker#tx}
         *                  and {@link Worker#ty} indices should identify the problematic tile.
         * @param  ex       the exception that occurred.
         */
        final void recordError(final Worker<RI,?,A> indices, final Throwable ex) {
            if (stopOnError) {
                set(Integer.MIN_VALUE);         // Will cause other threads to stop fetching tiles.
            }
            synchronized (this) {
                if (errors == null) {
                    errors = Resources.forLocale((Locale) null).getLogRecord(Level.WARNING,
                             Resources.Keys.CanNotProcessTile_2, indices.tx, indices.ty);
                    errors.setThrown(ex);
                } else {
                    errors.getThrown().addSuppressed(ex);
                }
            }
        }

        /**
         * Returns a string representation of this cursor for debugging purposes.
         */
        @Override
        public String toString() {
            final int index = get();
            String tile = "done";
            if (index >= 0) {
                final int tx = Math.addExact(minTileX, index % numXTiles);
                final int ty = Math.addExact(minTileY, index / numXTiles);
                if (ty <= maxTileY) {
                    tile = "(" + tx + ", " + ty + ')';
                }
            }
            return Strings.toString(getClass(),
                    "image",      Classes.getShortClassName(image),
                    "numWorkers", getNumWorkers(),
                    "tile",       tile);
        }
    }




    /**
     * Base class of workers which will read or write tiles. Exactly one {@code Worker} instance is
     * created for each thread which will perform the computation. The same {@code Worker} instance
     * can process an arbitrary amount of tiles.
     *
     * <p>Subclasses must override {@link #executeOnCurrentTile()}.</p>
     *
     * @param  <RI>  {@link RenderedImage} or {@link WritableRenderedImage}.
     * @param  <RT>  {@link Raster} or {@link WritableRaster}.
     * @param  <A>   type of the thread-local object (the accumulator) for holding intermediate results.
     */
    private abstract static class Worker<RI extends RenderedImage, RT extends Raster, A> implements Runnable {
        /**
         * An iterator over the indices of the next tiles to fetch. The same instance will be shared by all
         * {@link Worker} instances created by the same call to {@link ReadWork#execute ReadWork.execute(…)}
         * or {@link WriteWork#execute WriteWork.execute(…)}.
         */
        protected final Cursor<RI,A> cursor;

        /**
         * Indices of the tile to fetch. Those indices are updated by {@link Cursor#next(Worker)}.
         */
        protected int tx, ty;

        /**
         * The process to execute on each {@link Raster} or {@link WritableRaster}. Each invocation of
         * that process will also receive the {@link #accumulator} value, which is an instance unique
         * to each thread.
         */
        protected final BiConsumer<A, ? super RT> processor;

        /**
         * A thread-local variable which is given to each invocation of the {@link #processor}.
         * Processor implementation can use this instance for storing or updating information.
         * No synchronization is needed since this instance is not shared by other threads.
         * This value may be {@code null} if no such object is needed.
         */
        protected final A accumulator;

        /**
         * Creates a new worker for traversing the tiles identified by the given cursor.
         *
         * @param cursor     iterator over the indices of the tiles to fetch.
         * @param collector  provides the process to execute on each tile.
         */
        protected Worker(final Cursor<RI,A> cursor, final Collector<? super RT,A,?> collector) {
            this.cursor      = cursor;
            this.processor   = collector.accumulator();
            this.accumulator = collector.supplier().get();
        }

        /**
         * Invoked by {@link java.util.concurrent.ExecutorService#execute(Runnable)} for processing all tiles.
         * This method delegates to {@link #executeOnCurrentTile()} as long as there is tiles to process.
         * Exceptions are handled (wrapped in a {@link LogRecord} or propagated).
         */
        @Override
        public final void run() {
            while (cursor.next(this)) try {
                executeOnCurrentTile();
            } catch (Exception ex) {
                cursor.recordError(this, trimImagingWrapper(ex));
            }
            cursor.accumulate(accumulator);
        }

        /**
         * Gets the tiles at the ({@link #tx}, {@link #ty}) indices and processes it. If the process produces
         * a result other than updating pixel values (for example if the process is computing statistics),
         * then that result should be added to the {@link #accumulator} object.
         *
         * @throws RuntimeException if any error occurred during the process.
         */
        protected abstract void executeOnCurrentTile();

        /**
         * If the given exception can be propagated as an error or unchecked exception, throws it.
         * Otherwise wraps it in an {@link ImagingOpException} with intentionally no error message
         * (for allowing {@link #trimImagingWrapper(Throwable)} to recognize and unwrap it).
         *
         * @param  ex  the exception to propagate if possible.
         * @return the exception to throw if the given exception can not be propagated.
         */
        static ImagingOpException rethrowOrWrap(final Throwable ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            /*
             * May occur after call to `TileOpExecutor.readFrom(…)` or `TileOpExecutor.writeTo(…)`,
             * in which case that exception will be removed by `trimImagingWrapper(…)` method and
             * replaced by another exception providing a more complete error message.
             *
             * Should not happen in other contexts because the other exception handlers where this
             * `rethrowOrWrap(…)` method is invoked expect (indirectly) only unchecked exceptions.
             */
            return (ImagingOpException) new ImagingOpException(null).initCause(cause != null ? cause : ex);
        }
    }

    /**
     * If the given exception is a wrapper providing no useful information, returns its non-null cause.
     * Otherwise returns the given exception, possibly {@linkplain Exceptions#unwrap(Exception) unwrapped}.
     */
    private static Throwable trimImagingWrapper(Throwable ex) {
        while (ex.getClass() == ImagingOpException.class && ex.getMessage() == null && ex.getSuppressed().length == 0) {
            final Throwable cause = ex.getCause();
            if (cause == null) return ex;
            ex = cause;
        }
        if (ex instanceof Exception) {
            ex = Exceptions.unwrap((Exception) ex);
        }
        return ex;
    }




    /**
     * Worker which will read tiles. Exactly one {@code ReadWork} instance is created for each thread
     * which will perform the computation on {@link Raster} tiles. The same {@code ReadWork} instance
     * can process an arbitrary amount of tiles.
     *
     * @param  <A>   type of the thread-local object (the accumulator) for holding intermediate results.
     */
    private static final class ReadWork<A> extends Worker<RenderedImage, Raster, A> {
        /**
         * Creates a new worker for traversing the tiles identified by the given cursor.
         *
         * @param cursor     iterator over the indices of the tiles to fetch.
         * @param collector  provides the process to execute on each tile.
         */
        private ReadWork(final Cursor<RenderedImage,A> cursor, final Collector<? super Raster, A, ?> collector) {
            super(cursor, collector);
        }

        /**
         * Invoked by {@link Worker#run()} for processing the tile at current indices.
         *
         * @throws RuntimeException if any error occurred during the process.
         */
        @Override
        protected void executeOnCurrentTile() {
            final Raster tile = cursor.image.getTile(tx, ty);
            processor.accept(accumulator, tile);
        }

        /**
         * Implementation of {@link #executeOnReadable(RenderedImage, Collector, Consumer)}.
         * See the Javadoc of that method for details.
         */
        static <A,R> R execute(final TileOpExecutor executor, final RenderedImage source,
                final Collector<? super Raster, A, R> collector, final Consumer<LogRecord> errorHandler)
        {
            final Cursor<RenderedImage,A> cursor = executor.new Cursor<>(source, collector, errorHandler == null);
            final Future<?>[] workers = new Future<?>[cursor.getNumWorkers()];
            for (int i=0; i<workers.length; i++) {
                workers[i] = CommonExecutor.instance().submit(new ReadWork<>(cursor, collector));
            }
            final ReadWork<A> worker = new ReadWork<>(cursor, collector);
            worker.run();
            return cursor.finish(workers, collector, errorHandler);
        }
    }




    /**
     * Worker which will write tiles. Exactly one {@code WriteWork} instance is created for each thread
     * which will perform the operation on {@link WritableRaster} tiles. The same {@code WriteWork}
     * instance can process an arbitrary amount of tiles.
     *
     * @param  <A>   type of the thread-local object (the accumulator) for holding intermediate results.
     */
    private static final class WriteWork<A> extends Worker<WritableRenderedImage, WritableRaster, A> {
        /**
         * Creates a new worker for traversing the tiles identified by the given cursor.
         *
         * @param cursor     iterator over the indices of the tiles to fetch.
         * @param collector  provides the process to execute on each tile.
         */
        private WriteWork(final Cursor<WritableRenderedImage,A> cursor, final Collector<? super WritableRaster, A, ?> collector) {
            super(cursor, collector);
        }

        /**
         * Invoked by {@link Worker#run()} for processing the tile at current indices.
         *
         * @throws RuntimeException if any error occurred during the process.
         */
        @Override
        protected void executeOnCurrentTile() {
            final WritableRenderedImage image = cursor.image;
            final int tx = super.tx;                                // Protect from changes (paranoiac safety).
            final int ty = super.ty;
            final WritableRaster tile = image.getWritableTile(tx, ty);
            try {
                processor.accept(accumulator, tile);
            } finally {
                image.releaseWritableTile(tx, ty);
            }
        }

        /**
         * Implementation of {@link #executeOnWritable(WritableRenderedImage, Collector, Consumer)}.
         * See the Javadoc of that method for details.
         */
        static <A,R> R execute(final TileOpExecutor executor, final WritableRenderedImage target,
                final Collector<? super WritableRaster,A,R> collector, final Consumer<LogRecord> errorHandler)
        {
            final Cursor<WritableRenderedImage,A> cursor = executor.new Cursor<>(target, collector, false);
            final Future<?>[] workers = new Future<?>[cursor.getNumWorkers()];
            for (int i=0; i<workers.length; i++) {
                workers[i] = CommonExecutor.instance().submit(new WriteWork<>(cursor, collector));
            }
            final WriteWork<A> worker = new WriteWork<>(cursor, collector);
            worker.run();
            return cursor.finish(workers, collector, errorHandler);
        }
    }
}