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

import java.util.Map;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.awt.Transparency;
import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.PackedColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import org.apache.sis.coverage.Category;
import org.apache.sis.coverage.SampleDimension;
import org.apache.sis.measure.NumberRange;
import org.apache.sis.util.ArraysExt;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.collection.WeakHashSet;
import org.apache.sis.util.collection.WeakValueHashMap;
import org.apache.sis.util.Debug;


/**
 * A factory for {@link ColorModel} objects built from a sequence of colors.
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @author  Johann Sorel (Geomatys)
 * @version 1.1
 * @since   1.0
 * @module
 */
public final class ColorModelFactory {
    /**
     * The fully transparent color.
     */
    public static final Color TRANSPARENT = new Color(0, true);

    /**
     * Applies a gray scale to quantitative category and transparent colors to qualitative categories.
     * This is a possible argument for {@link #createColorModel(SampleDimension[], int, int, Function)}.
     */
    public static final Function<Category,Color[]> GRAYSCALE =
            (category) -> category.isQuantitative() ? new Color[] {Color.BLACK, Color.WHITE} : null;

    /**
     * Shared instances of {@link ColorModel}s. Maintaining shared instance is not that much interesting
     * for most kind of color models, except {@link IndexColorModel} which can potentially be quite big.
     * This class works for all color models because they were no technical reasons to restrict, but the
     * real interest is to share index color models.
     *
     * @see #unique(ColorModel)
     */
    @SuppressWarnings("rawtypes")   // TODO: Remove after we removed ColorModelPatch.
    private static final WeakHashSet<ColorModelPatch> CACHE = new WeakHashSet<>(ColorModelPatch.class);

    /**
     * A pool of color models previously created by {@link #createColorModel()}.
     *
     * <div class="note"><b>Note:</b>
     * we use {@linkplain java.lang.ref.WeakReference weak references} instead of {@linkplain java.lang.ref.SoftReference
     * soft references} because the intent is not to cache the values. The intent is to share existing instances in order
     * to reduce memory usage. Rational:
     *
     * <ul>
     *   <li>{@link ColorModel} may consume a lot of memory. A 16 bits indexed color model can consume up to 256 kb.
     *       We do not want to retain such large objects longer than necessary. We want to share existing instances
     *       without preventing the garbage collector to collect them.</li>
     *   <li>{@link #createColorModel()} is reasonably fast if invoked only occasionally, so it is not worth consuming 256 kb
     *       for saving the few milliseconds requiring for building a new color model. Client code should retains their own
     *       reference to a {@link ColorModel} if they plan to reuse it often in a short period of time.</li>
     * </ul>
     * </div>
     */
    private static final Map<ColorModelFactory,ColorModel> PIECEWISES = new WeakValueHashMap<>(ColorModelFactory.class);

    /**
     * Comparator for sorting ranges by their minimal value.
     */
    private static final Comparator<Map.Entry<NumberRange<?>, Color[]>> RANGE_COMPARATOR =
            (r1, r2) -> Double.compare(r1.getKey().getMinDouble(true),
                                       r2.getKey().getMinDouble(true));

    /**
     * The minimum (inclusive) and maximum (exclusive) sample values.
     */
    private final double minimum, maximum;

    /**
     * In a color map defined by a piecewise function, indices where to store the first interpolated value in the color map.
     * The number of pieces (segments) is {@code pieceStarts.length}. The last element of this array is the index after the
     * end of the last piece. The indices are integers. Never {@code null} but may be empty.
     *
     * <div class="note"><b>Note:</b>
     * indices as unsigned short are not sufficient since in the worst case the last next index will
     * be 65536, which would be converted to 0 as a short, causing several exception afterward.</div>
     */
    private final int[] pieceStarts;

    /**
     * The Alpha-Red-Green-Blue codes for all segments of the piecewise function.
     * This is {@code null} if {@link #pieceStarts} is empty.
     */
    private final int[][] ARGB;

    /**
     * The visible band (usually 0) used for the construction of a single instance of a {@link ColorModel}.
     */
    private final int visibleBand;

    /**
     * The number of bands (usually 1) used for the construction of a single instance of a {@link ColorModel}.
     */
    private final int numBands;

    /**
     * The color model type. One of the following types:
     * <ul>
     *   <li>{@link DataBuffer#TYPE_BYTE}  or {@link DataBuffer#TYPE_USHORT}: will create a {@link IndexColorModel} (unless grayscale).</li>
     *   <li>{@link DataBuffer#TYPE_FLOAT} or {@link DataBuffer#TYPE_DOUBLE}: will create a {@link ComponentColorModel}.</li>
     *   <li>{@link DataBuffer#TYPE_INT}: should create a {@link PackedColorModel} according {@link java.awt.image.Raster} javadoc
     *        (for compatibility with {@code Raster.createPackedRaster(…)}), but we nevertheless create {@link ComponentColorModel}
     *        for the 1-banded sample model created by {@link RasterFactory}.</li>
     * </ul>
     *
     * @todo The user may want to set explicitly the number of bits each pixel occupies.
     *       We need to think about an API to allows that.
     */
    private final int type;

    /**
     * Constructs a new {@code ColorModelFactory}. This object will be used as a key in a {@link Map},
     * so this is not really a {@code ColorModelFactory} but a kind of "{@code ColorModelKey}" instead.
     * However, since this constructor is private, user does not need to know that.
     */
    private ColorModelFactory(final Map<? extends NumberRange<?>, ? extends Color[]> categories,
                              final int visibleBand, final int numBands, final int type)
    {
        this.visibleBand = visibleBand;
        this.numBands    = numBands;
        this.type        = type;
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Map.Entry<NumberRange<?>, Color[]>[] entries = categories.entrySet().toArray(new Map.Entry[categories.size()]);
        Arrays.sort(entries, RANGE_COMPARATOR);
        int     count   = 0;
        int[]   starts  = new int[entries.length + 1];
        int[][] codes   = new int[entries.length][];
        double  minimum = Double.POSITIVE_INFINITY;
        double  maximum = Double.NEGATIVE_INFINITY;
        for (final Map.Entry<NumberRange<?>, Color[]> entry : entries) {
            final NumberRange<?> range = entry.getKey();
            final double min = range.getMinDouble(true);
            final double max = range.getMaxDouble(false);
            if (min < minimum) minimum = min;
            if (max > maximum) maximum = max;
            final int lower = Math.round((float) min);
            final int upper = Math.round((float) max);
            if (lower < upper) {
                if (lower < 0 || upper > 0x10000) {
                    starts = ArraysExt.EMPTY_INT;
                    codes  = null;
                    count  = 0;
                } else if (codes != null) {
                    if (count != 0) {
                        final int before = starts[count];
                        if (before != lower) {
                            if (before > lower) {
                                // TODO: remove the overlapped colors in previous range.
                            } else {
                                // TODO: we could reduce the amount of copies.
                                codes  = Arrays.copyOf(codes,   codes.length + 1);
                                starts = Arrays.copyOf(starts, starts.length + 1);
                                codes[count++] = ArraysExt.EMPTY_INT;
                            }
                        }
                    }
                    codes [  count] = toARGB(entry.getValue());
                    starts[  count] = lower;
                    starts[++count] = upper;
                }
            }
        }
        if (minimum >= maximum) {
            minimum = 0;
            maximum = 1;
        }
        /*
         * The length of 'pieceStarts' may differ from the expected length if there is holes between categories.
         * We need to adjust the array length since it will determine the number of categories. Note that there
         * is one more element than the number of categories.
         */
        if (starts.length != 0) {
            starts = ArraysExt.resize(starts, count + 1);
        }
        this.minimum     = minimum;
        this.maximum     = maximum;
        this.pieceStarts = starts;
        this.ARGB        = codes;
    }

    /**
     * Constructs the color model from the {@code #codes} and {@link #ARGB} data.
     * This method is invoked the first time the color model is created, or when
     * the value in the cache has been discarded.
     */
    private ColorModel createColorModel() {
        /*
         * If the requested type is any type not supported by IndexColorModel,
         * fallback on a generic (but very slow!) color model.
         */
        if (type != DataBuffer.TYPE_BYTE && type != DataBuffer.TYPE_USHORT) {
            return createGrayScale(type, numBands, visibleBand, minimum, maximum);
        }
        /*
         * If there is no category, constructs a gray scale palette.
         */
        final int categoryCount = pieceStarts.length - 1;
        if (numBands == 1 && categoryCount <= 0) {
            final ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            final int[] nBits = {
                DataBuffer.getDataTypeSize(type)
            };
            return unique(new ComponentColorModel(cs, nBits, false, true, Transparency.OPAQUE, type));
        }
        /*
         * Interpolates the colors in the color palette. Colors that do not fall
         * in the range of a category will be set to a transparent color.
         */
        final int[] colorMap;
        int transparent = -1;
        if (categoryCount <= 0) {
            colorMap = ArraysExt.range(0, 256);
        } else {
            colorMap = new int[pieceStarts[categoryCount]];
            for (int i=0; i<categoryCount; i++) {
                final int[] colors = ARGB[i];
                final int   lower  = pieceStarts[i  ];
                final int   upper  = pieceStarts[i+1];
                if (transparent < 0 && colors.length == 0) {
                    transparent = lower;
                }
                expand(colors, colorMap, lower, upper);
            }
        }
        return createIndexColorModel(colorMap, numBands, visibleBand, transparent);
    }

    /**
     * Public as an implementation side-effect.
     *
     * @return a hash code.
     */
    @Override
    public int hashCode() {
        final int categoryCount = pieceStarts.length - 1;
        int code = 962745549 + (numBands*31 + visibleBand)*31 + categoryCount;
        for (int i=0; i<categoryCount; i++) {
            code += Arrays.hashCode(ARGB[i]);
        }
        return code;
    }

    /**
     * Public as an implementation side-effect.
     *
     * @param  other the other object to compare for equality.
     * @return whether the two objects are equal.
     */
    @Override
    public boolean equals(final Object other) {
        if (other == this) {
            return true;
        }
        if (other instanceof ColorModelFactory) {
            final ColorModelFactory that = (ColorModelFactory) other;
            return this.type        == that.type
                && this.numBands    == that.numBands
                && this.visibleBand == that.visibleBand
                && this.minimum     == that.minimum         // Should never be NaN.
                && this.maximum     == that.maximum
                && Arrays.equals(pieceStarts, that.pieceStarts)
                && Arrays.deepEquals(ARGB, that.ARGB);
        }
        return false;
    }

    /**
     * Returns a color model interpolated for the ranges in the given sample dimensions.
     * This method builds up the color model from each category in the visible sample dimension.
     * Returned instances of {@link ColorModel} are shared among all callers in the running virtual machine.
     *
     * @param  bands        the sample dimensions for which to create a color model.
     * @param  visibleBand  the band to be made visible (usually 0). All other bands (if any) will be ignored.
     * @param  type         the color model type. One of {@link DataBuffer#TYPE_BYTE}, {@link DataBuffer#TYPE_USHORT},
     *                      {@link DataBuffer#TYPE_INT}, {@link DataBuffer#TYPE_FLOAT} or {@link DataBuffer#TYPE_DOUBLE}.
     * @param  colors       the colors to use for each category. The function may return {@code null}, which means transparent.
     * @return a color model suitable for {@link java.awt.image.RenderedImage} objects with values in the given ranges.
     */
    public static ColorModel createColorModel(final SampleDimension[] bands,
            final int visibleBand, final int type, Function<Category,Color[]> colors)
    {
        ArgumentChecks.ensureNonNull("bands",  bands);
        ArgumentChecks.ensureNonNull("colors", colors);
        final Map<NumberRange<?>, Color[]> ranges = new LinkedHashMap<>();
        for (final Category category : bands[visibleBand].getCategories()) {
            ranges.put(category.getSampleRange(), colors.apply(category));
        }
        return createColorModel(ranges, visibleBand, bands.length, type);
    }

    /**
     * Returns a color model interpolated for the given ranges and colors.
     * This method builds up the color model from each set of colors associated to ranges in the given map.
     * Returned instances of {@link ColorModel} are shared among all callers in the running virtual machine.
     *
     * @param  categories   the colors associated to ranges of sample values.
     * @param  visibleBand  the band to be made visible (usually 0). All other bands, if any will be ignored.
     * @param  numBands     the number of bands for the color model (usually 1). The returned color model will render only
     *                      the {@code visibleBand} and ignore the others, but the existence of all {@code numBands} will
     *                      be at least tolerated. Supplemental bands, even invisible, are useful for processing.
     * @param  type         the color model type. One of {@link DataBuffer#TYPE_BYTE}, {@link DataBuffer#TYPE_USHORT},
     *                      {@link DataBuffer#TYPE_INT}, {@link DataBuffer#TYPE_FLOAT} or {@link DataBuffer#TYPE_DOUBLE}.
     * @return a color model suitable for {@link java.awt.image.RenderedImage} objects with values in the given ranges.
     */
    public static ColorModel createColorModel(final Map<? extends NumberRange<?>, ? extends Color[]> categories,
            final int visibleBand, final int numBands, final int type)
    {
        ArgumentChecks.ensureNonNull("categories", categories);
        ArgumentChecks.ensureBetween("visibleBand", 0, numBands - 1, visibleBand);
        final ColorModelFactory key = new ColorModelFactory(categories, visibleBand, numBands, type);
        synchronized (PIECEWISES) {
            ColorModel model = PIECEWISES.get(key);
            if (model == null) {
                model = key.createColorModel();
                PIECEWISES.put(key, model);
            }
            return model;
        }
    }

    /**
     * Returns a tolerant index color model for the specified ARGB code.
     * This color model accepts image with the specified number of bands.
     *
     * <p>This methods caches previously created instances using weak references,
     * because index color model may be big (up to 256 kb).</p>
     *
     * @param  ARGB         an array of ARGB values.
     * @param  numBands     the number of bands.
     * @param  visibleBand  the band to display.
     * @param  transparent  the transparent pixel, or -1 for auto-detection.
     * @return An index color model for the specified array.
     */
    public static IndexColorModel createIndexColorModel(final int[] ARGB, final int numBands, final int visibleBand, int transparent) {
        /*
         * No need to scan the ARGB values in search of a transparent pixel;
         * the IndexColorModel constructor does that for us.
         */
        final int length = ARGB.length;
        final int bits = getBitCount(length);
        final int type = getTransferType(length);
        final IndexColorModel cm;
        if (numBands == 1) {
            cm = new IndexColorModel(bits, length, ARGB, 0, true, transparent, type);
        } else {
            cm = new MultiBandsIndexColorModel(bits, length, ARGB, 0, true, transparent,
                                               type, numBands, visibleBand);
        }
        return unique(cm);
    }

    /**
     * Creates a color model for opaque images storing pixels as real numbers.
     * The color model can have an arbitrary number of bands, but in current implementation only one band is used.
     *
     * <p><b>Warning:</b> the use of this color model is very slow.
     * It should be used only when no standard color model can be used.</p>
     *
     * @param  dataType       the color model type as one of {@code DataBuffer.TYPE_*} constants.
     * @param  numComponents  the number of components.
     * @param  visibleBand    the band to use for computing colors.
     * @param  minimum        the minimal sample value expected.
     * @param  maximum        the maximal sample value expected.
     * @return the color model for the given range of values.
     *
     * @see RasterFactory#createGrayScaleImage(int, int, int, int, int, double, double)
     */
    public static ColorModel createGrayScale(final int dataType, final int numComponents,
            final int visibleBand, final double minimum, final double maximum)
    {
        final ColorModel cm;
        if (numComponents == 1 && isStandardRange(dataType, minimum, maximum)) {
            final ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            cm = new ComponentColorModel(cs, false, true, Transparency.OPAQUE, dataType);
        } else {
            final ScaledColorSpace cs = new ScaledColorSpace(numComponents, visibleBand, minimum, maximum);
            cm = new ScaledColorModel(cs, dataType);
        }
        return unique(cm);
    }

    /**
     * Returns {@code true} if the given range of values is the standard range for the given data type.
     * In such case it may be possible to use a Java standard color model, which sometime benefit from
     * acceleration in Java2D rendering pipe.
     *
     * <p>This method does not clamp the given values to the maximum range supported by the given type.
     * For example even if {@code TYPE_BYTE} can not represent values outside the [0 … 255] range,
     * we do not clamp the minimum and maximum values to that range because it would change the visual
     * appearance (because of different color scale).</p>
     *
     * @param  dataType  one of {@link DataBuffer} constants.
     * @param  minimum   the minimal sample value expected.
     * @param  maximum   the maximal sample value expected.
     * @return whether the given minimum and maximum are the standard range for the given type.
     */
    static boolean isStandardRange(final int dataType, double minimum, final double maximum) {
        final boolean signed;
        switch (dataType) {
            case DataBuffer.TYPE_BYTE:
            case DataBuffer.TYPE_USHORT: signed = false; break;
            case DataBuffer.TYPE_SHORT:
            case DataBuffer.TYPE_INT:    signed = true; break;
            case DataBuffer.TYPE_FLOAT:
            case DataBuffer.TYPE_DOUBLE: return ((float) minimum) == 0f && ((float) maximum) == 1f;
            default: return false;
        }
        final int numBits = DataBuffer.getDataTypeSize(dataType);
        final double upper;     // Exclusive (e.g. 256 or 65536)
        if (signed) {
            upper = 1L << (numBits - 1);        // E.g. 128 for TYPE_BYTE.
            minimum += upper;                   // Convert e.g. -128 to 0.
        } else {
            upper = 1L << numBits;              // E.g. 256 for TYPE_BYTE.
        }
        /*
         * Since sample values are integers, take a tolerance of 1. But for the upper bounds,
         * we take a slightly larger tolerance in case the caller confused "inclusive" versus
         * "exclusive" values. For example 255.5 ± 1.5 accepts the |254.001 … 256.999] range.
         */
        return Math.abs(minimum) < 1 && Math.abs(maximum - (upper - 0.5)) < 1.5;
    }

    /**
     * Appends a description of the given color space in the given buffer.
     * This is used for {@code toString()} method implementations.
     *
     * @param  cs      the color space to describe, or {@code null}.
     * @param  buffer  where to append the description.
     */
    @Debug
    public static void formatDescription(final ColorSpace cs, final StringBuilder buffer) {
        if (cs != null) {
            if (cs instanceof ScaledColorSpace) {
                ((ScaledColorSpace) cs).formatRange(buffer.append("showing "));
            } else if (cs.getType() == ColorSpace.TYPE_GRAY) {
                buffer.append("grayscale");
            }
        }
    }

    /**
     * Returns a unique instance of the given color model. This method is automatically invoked by {@code create(…)} methods
     * in this class. This {@code unique(ColorModel)} method is public for use by color models created by other ways.
     *
     * @param  <T>  the type of the color model to share.
     * @param  cm   the color model for which to get a unique instance.
     * @return a unique (shared) instance of the given color model.
     */
    public static <T extends ColorModel> T unique(T cm) {
        ColorModelPatch<T> c = new ColorModelPatch<>(cm);
        c = CACHE.unique(c);
        return c.cm;
    }

    /**
     * Returns a suggested type for an {@link IndexColorModel} of {@code mapSize} colors.
     * This method returns {@link DataBuffer#TYPE_BYTE} or {@link DataBuffer#TYPE_USHORT}.
     *
     * @param  mapSize  the number of colors in the map.
     * @return the suggested transfer type.
     */
    private static int getTransferType(final int mapSize) {
        return (mapSize <= 256) ? DataBuffer.TYPE_BYTE : DataBuffer.TYPE_USHORT;
    }

    /**
     * Returns a bit count for an {@link IndexColorModel} mapping {@code mapSize} colors.
     * It is guaranteed that the following relation is hold:
     *
     * {@preformat java
     *     (1 << getBitCount(mapSize)) >= mapSize
     * }
     *
     * @param  mapSize  the number of colors in the map.
     * @return the number of bits to use.
     */
    public static int getBitCount(final int mapSize) {
        final int count = Integer.SIZE - Integer.numberOfLeadingZeros(mapSize - 1);
        assert (1 << count) >= mapSize : mapSize;
        assert (1 << (count-1)) < mapSize : mapSize;
        return Math.max(1, count);
    }

    /**
     * Returns the ARGB codes for the given colors. If all colors are transparent, returns an empty array.
     *
     * @param  colors  the colors to convert to ARGB codes, or {@code null}.
     * @return ARGB codes for the given colors. Never {@code null} but may be empty.
     */
    private static int[] toARGB(final Color[] colors) {
        if (colors != null) {
            int combined = 0;
            final int[] ARGB = new int[colors.length];
            for (int i=0; i<ARGB.length; i++) {
                final Color color = colors[i];
                if (color != null) {
                    int c = color.getRGB();                         // Note: getRGB() is really getARGB().
                    combined |= c;
                    ARGB[i]   = c;
                }
            }
            if ((combined & 0xFF000000) != 0) {
                return ARGB;
            }
        }
        return ArraysExt.EMPTY_INT;
    }

    /**
     * Copies {@code colors} into {@code ARGB} array from index {@code lower} inclusive to index {@code upper} exclusive.
     * If {@code upper-lower} is not equal to the length of {@code colors} array, then colors will be interpolated.
     *
     * @param  colors  colors to copy into the {@code ARGB} array.
     * @param  ARGB    array of integer to write ARGB values into.
     * @param  lower   index (inclusive) of the first element of {@code ARGB} to change.
     * @param  upper   index (exclusive) of the last  element of {@code ARGB} to change.
     */
    @SuppressWarnings("fallthrough")
    public static void expand(final int[] colors, final int[] ARGB, final int lower, final int upper) {
        switch (colors.length) {
            case 1: Arrays.fill(ARGB, lower, upper, colors[0]);         // fall through
            case 0: return;
        }
        switch (upper - lower) {
            case 1: ARGB[lower] = colors[0];                            // fall through
            case 0: return;
        }
        /*
         * Prepares the coefficients for the iteration.
         * The non-final ones will be updated inside the loop.
         */
        final double scale = (double) (colors.length - 1) / (double) (upper - lower - 1);
        final int maxBase = colors.length - 2;
        float index = 0;
        int   base  = 0;
        for (int i=lower;;) {
            final int C0 = colors[base    ];
            final int C1 = colors[base + 1];
            final int A0 = (C0 >>> 24) & 0xFF,   A1 = ((C1 >>> 24) & 0xFF) - A0;
            final int R0 = (C0 >>> 16) & 0xFF,   R1 = ((C1 >>> 16) & 0xFF) - R0;
            final int G0 = (C0 >>>  8) & 0xFF,   G1 = ((C1 >>>  8) & 0xFF) - G0;
            final int B0 = (C0       ) & 0xFF,   B1 = ((C1       ) & 0xFF) - B0;
            final int oldBase = base;
            do {
                final float delta = index - base;
                ARGB[i] = (roundByte(A0 + delta*A1) << 24) |
                          (roundByte(R0 + delta*R1) << 16) |
                          (roundByte(G0 + delta*G1) <<  8) |
                          (roundByte(B0 + delta*B1));
                if (++i == upper) {
                    return;
                }
                index = (float) ((i - lower) * scale);
                base = Math.min(maxBase, (int) (index + Math.ulp(index)));          // Really want rounding toward 0.
            } while (base == oldBase);
        }
    }

    /**
     * Rounds a float value and clamps the result between 0 and 255 inclusive.
     *
     * @param  value  the value to round.
     * @return the rounded and clamped value.
     */
    private static int roundByte(final float value) {
        return Math.min(Math.max(Math.round(value), 0), 255);
    }
}