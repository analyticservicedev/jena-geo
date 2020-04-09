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
package org.apache.sis.coverage.grid;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Optional;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import org.opengis.geometry.DirectPosition;
import org.opengis.coverage.CannotEvaluateException;
import org.opengis.referencing.operation.MathTransform1D;
import org.opengis.referencing.operation.TransformException;
import org.opengis.referencing.operation.NoninvertibleTransformException;
import org.apache.sis.referencing.operation.transform.MathTransforms;
import org.apache.sis.coverage.SampleDimension;
import org.apache.sis.internal.coverage.j2d.BandedSampleConverter;
import org.apache.sis.internal.coverage.j2d.RasterFactory;
import org.apache.sis.measure.NumberRange;


/**
 * Decorates a {@link GridCoverage} in order to convert sample values on the fly.
 * There is two strategies about when to convert sample values:
 *
 * <ul>
 *   <li>In calls to {@link #render(GridExtent)}, sample values are converted when first needed
 *       on a tile-by-tile basis then cached for future reuse. Note however that discarding the
 *       returned image may result in the lost of cached tiles.</li>
 *   <li>In calls to {@link #evaluate(DirectPosition, double[])}, the conversion is applied
 *       on-the-fly each time in order to avoid the potentially costly tile computations.</li>
 * </ul>
 *
 * @author  Johann Sorel (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 * @since   1.0
 * @module
 */
final class ConvertedGridCoverage extends GridCoverage {
    /**
     * The band to make visible when the image is shown on screen.
     * All other bands, if any, will be computed their result ignored at display time.
     */
    static final int VISIBLE_BAND = 0;

    /**
     * The coverage containing source values.
     * Sample values will be converted from that coverage using the {@link #converters}.
     */
    private final GridCoverage source;

    /**
     * Conversions from {@link #source} values to converted values.
     * The length of this array shall be equal to the number of bands.
     */
    private final MathTransform1D[] converters;

    /**
     * Whether this grid coverage is for converted values.
     * If {@code false}, then this coverage is for packed values.
     */
    private final boolean isConverted;

    /**
     * One of {@link DataBuffer} constants the describe the sample values type
     * of images produced by {@link #render(GridExtent)}.
     */
    private final int dataType;

    /**
     * Creates a new coverage with the same grid geometry than the given coverage but converted sample dimensions.
     *
     * @param  source       the coverage containing source values.
     * @param  range        the sample dimensions to assign to the converted grid coverage.
     * @param  converters   conversion from source to converted coverage, one transform per band.
     * @param  isConverted  whether this grid coverage is for converted or packed values.
     */
    private ConvertedGridCoverage(final GridCoverage source, final List<SampleDimension> range,
                                  final MathTransform1D[] converters, final boolean isConverted)
    {
        super(source.getGridGeometry(), range);
        this.source      = source;
        this.converters  = converters;
        this.isConverted = isConverted;
        this.dataType    = getDataType(range, isConverted);
    }

    /**
     * Returns a coverage of converted values computed from a coverage of packed values, or conversely.
     * If the given coverage is already converted, then this method returns {@code coverage} unchanged.
     *
     * @param  source     the coverage containing values to convert.
     * @param  converted  {@code true} for a coverage containing converted values,
     *                    or {@code false} for a coverage containing packed values.
     * @return the converted coverage. May be {@code source}.
     * @throws NoninvertibleTransformException if this constructor can not build a full conversion chain to target.
     */
    static GridCoverage create(final GridCoverage source, final boolean converted) throws NoninvertibleTransformException {
        final List<SampleDimension> sources = source.getSampleDimensions();
        final List<SampleDimension> targets = new ArrayList<>(sources.size());
        final MathTransform1D[]  converters = converters(sources, targets, converted);
        return (converters != null) ? new ConvertedGridCoverage(source, targets, converters, converted) : source;
    }

    /**
     * Returns the transforms for converting sample values from given sources to the {@code converted} status
     * of those sources. This method opportunistically adds the target sample dimensions in {@code target} list.
     *
     * @param  sources    {@link GridCoverage#getSampleDimensions()} of {@code source} coverage.
     * @param  targets    where to add {@link SampleDimension#forConvertedValues(boolean)} results.
     * @param  converted  {@code true} for transforms to converted values, or {@code false} for transforms to packed values.
     * @return the transforms, or {@code null} if all transforms are identity transform.
     * @throws NoninvertibleTransformException if this method can not build a full conversion chain.
     */
    static MathTransform1D[] converters(final List<SampleDimension> sources,
                                        final List<SampleDimension> targets,
                                        final boolean converted)
            throws NoninvertibleTransformException
    {
        final int               numBands   = sources.size();
        final MathTransform1D   identity   = (MathTransform1D) MathTransforms.identity(1);
        final MathTransform1D[] converters = new MathTransform1D[numBands];
        Arrays.fill(converters, identity);
        for (int i = 0; i < numBands; i++) {
            final SampleDimension src = sources.get(i);
            final SampleDimension tgt = src.forConvertedValues(converted);
            targets.add(tgt);
            if (src != tgt) {
                MathTransform1D tr = src.getTransferFunction().orElse(identity);
                Optional<MathTransform1D> complete = tgt.getTransferFunction();
                if (complete.isPresent()) {
                    tr = MathTransforms.concatenate(tr, complete.get().inverse());
                }
                converters[i] = tr;
            }
        }
        for (final MathTransform1D converter : converters) {
            if (!converter.isIdentity()) return converters;
        }
        return null;
    }

    /**
     * Returns the {@link DataBuffer} constant for range of values of given sample dimensions.
     *
     * @param  targets    the sample dimensions for which to get the data type.
     * @param  converted  whether the image will hold converted or packed values.
     * @return the {@link DataBuffer} type.
     */
    public static int getDataType(final List<SampleDimension> targets, final boolean converted) {
        NumberRange<?> union = null;
        for (final SampleDimension dimension : targets) {
            final Optional<NumberRange<?>> c = dimension.getSampleRange();
            if (c.isPresent()) {
                final NumberRange<?> range = c.get();
                if (union == null) {
                    union = range;
                } else {
                    union = union.unionAny(range);
                }
            }
        }
        return RasterFactory.getDataType(union, converted);
    }

    /**
     * Returns a sequence of double values for a given point in the coverage.
     * This method delegates to the source coverage, then convert values.
     *
     * @param  point   the coordinate point where to evaluate.
     * @param  buffer  an array in which to store values, or {@code null} to create a new array.
     * @return the {@code buffer} array, or a newly created array if {@code buffer} was null.
     * @throws CannotEvaluateException if the values can not be computed.
     */
    @Override
    public double[] evaluate(final DirectPosition point, double[] buffer) throws CannotEvaluateException {
        try {
            buffer = source.evaluate(point, buffer);
            for (int i=0; i<converters.length; i++) {
                buffer[i] = converters[i].transform(buffer[i]);
            }
        } catch (TransformException ex) {
            throw new CannotEvaluateException(ex.getMessage(), ex);
        }
        return buffer;
    }

    /**
     * Creates a converted view over {@link #source} data for the given extent.
     * Values will be converted when first requested on a tile-by-tile basis.
     * Note that if the returned image is discarded, then the cache of converted
     * tiles will be discarded too.
     *
     * @return the grid slice as a rendered image with converted view.
     */
    @Override
    public RenderedImage render(final GridExtent sliceExtent) {
        RenderedImage image = source.render(sliceExtent);
        /*
         * That image should never be null. But if an implementation wants to do so, respect that.
         */
        if (image != null) {
            final ColorModel colorModel = createColorModel(VISIBLE_BAND, dataType);
            image = BandedSampleConverter.create(image, null, dataType, colorModel, getRanges(), converters);
        }
        return image;
    }

    /**
     * Returns this coverage or the source coverage depending on whether {@code converted} matches
     * the kind of content of this coverage.
     */
    @Override
    public GridCoverage forConvertedValues(final boolean converted) {
        return (converted == isConverted) ? this : source;
    }
}