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

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.sis.coverage.SampleDimension;
import org.apache.sis.geometry.ImmutableEnvelope;
import org.apache.sis.internal.coverage.j2d.BufferedGridCoverage;
import org.apache.sis.internal.coverage.j2d.ColorModelFactory;
import org.apache.sis.internal.util.DoubleDouble;
import org.apache.sis.referencing.operation.matrix.Matrices;
import org.apache.sis.referencing.operation.matrix.MatrixSIS;
import org.apache.sis.referencing.operation.transform.MathTransforms;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.ArraysExt;
import org.opengis.geometry.Envelope;
import org.opengis.metadata.spatial.DimensionNameType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

/**
 * Helper class for the creation of {@link GridCoverage} instances. This builder can creates the
 * parameters to be given to {@linkplain GridCoverage2D} and {@linkplain BufferedGridCoverage}
 * from simpler parameters given to this builder.
 *
 * @author Johann Sorel (Geomatys)
 */
public class GridCoverageBuilder2 {

    private List<SampleDimension> ranges;
    private WritableRaster raster;
    private RenderedImage image;
    private DataBuffer buffer;
    private int bufferWidth = -1;
    private int bufferHeight = -1;
    private int bufferNbSample = -1;
    private GridGeometry grid;
    private final Set<Integer> flippedAxis = new HashSet<>();

    /**
     * Sets coverage data rendered image.
     *
     * @param image The rendered image to be wrapped by {@code GridCoverage2D}, not {@code null}.
     */
    public GridCoverageBuilder2 setValues(RenderedImage image) {
        ArgumentChecks.ensureNonNull("image", image);
        this.image = image;
        this.raster = null;
        this.buffer = null;
        this.bufferWidth = -1;
        this.bufferHeight = -1;
        this.bufferNbSample = -1;
        return this;
    }

    /**
     * Sets coverage data raster.
     *
     * @param raster The raster to be wrapped by {@code GridCoverage2D}, not {@code null}.
     */
    public GridCoverageBuilder2 setValues(WritableRaster raster) {
        ArgumentChecks.ensureNonNull("raster", raster);
        this.image = null;
        this.raster = raster;
        this.buffer = null;
        this.bufferWidth = -1;
        this.bufferHeight = -1;
        this.bufferNbSample = -1;
        return this;
    }

//    /**
//     * Creates a coverage from the given matrix.
//     * This method copies the values from the given matrix to a new DataBuffer.
//     * <p>
//     * The coverage height will be the length of the {@code matrix} argument.
//     * The coverage width will be the length of the first row, all rows are expected
//     * to have the same length.
//     *
//     * @param matrix The matrix data in a {@code [row][column]} layout.
//     * @throws ArithmeticException if the buffer size exceeds the {@code int} capacity.
//     */
//    public void setValues(int[][] matrix) throws ArithmeticException {
//        final int height = matrix.length;
//        final int width = matrix[0].length;
//        final int[] datas = new int[Math.multiplyExact(height,width)];
//        for (int i = 0, offset=0; i < matrix.length; i++,offset+=width) {
//            System.arraycopy(matrix[i], 0, datas, offset, width);
//        }
//        final DataBuffer buffer = new DataBufferInt(datas, datas.length);
//        setValues(buffer, width, height);
//    }
//
//    /**
//     * Creates a coverage from the given matrix.
//     * This method copies the values from the given matrix to a new DataBuffer.
//     * <p>
//     * The coverage height will be the length of the {@code matrix} argument.
//     * The coverage width will be the length of the first row, all rows are expected
//     * to have the same length.
//     *
//     * @param matrix The matrix data in a {@code [row][column]} layout.
//     * @throws ArithmeticException if the buffer size exceeds the {@code int} capacity.
//     */
//    public void setValues(float[][] matrix) throws ArithmeticException {
//        final int height = matrix.length;
//        final int width = matrix[0].length;
//        final float[] datas = new float[Math.multiplyExact(height,width)];
//        for (int i = 0, offset=0; i < matrix.length; i++,offset+=width) {
//            System.arraycopy(matrix[i], 0, datas, offset, width);
//        }
//        final DataBuffer buffer = new DataBufferFloat(datas, datas.length);
//        setValues(buffer, width, height);
//    }
//
//    /**
//     * Creates a coverage from the given matrix.
//     * This method copies the values from the given matrix to a new DataBuffer.
//     * <p>
//     * The coverage height will be the length of the {@code matrix} argument.
//     * The coverage width will be the length of the first row, all rows are expected
//     * to have the same length.
//     *
//     * @param matrix The matrix data in a {@code [row][column]} layout.
//     * @throws ArithmeticException if the buffer size exceeds the {@code int} capacity.
//     */
//    public void setValues(double[][] matrix) throws ArithmeticException {
//        final int height = matrix.length;
//        final int width = matrix[0].length;
//        final double[] datas = new double[Math.multiplyExact(height,width)];
//        for (int i = 0, offset=0; i < matrix.length; i++,offset+=width) {
//            System.arraycopy(matrix[i], 0, datas, offset, width);
//        }
//        final DataBuffer buffer = new DataBufferDouble(datas, datas.length);
//        setValues(buffer, width, height);
//    }

    /**
     * Creates a coverage from the given buffer.
     * This method uses the given buffer unmodified to create the coverage.
     *
     * @param data the coverage datas, not {@code null}.
     */
    public GridCoverageBuilder2 setValues(DataBuffer data) {
        ArgumentChecks.ensureNonNull("data", data);
        this.image = null;
        this.raster = null;
        this.buffer = data;
        this.bufferWidth = -1;
        this.bufferHeight = -1;
        this.bufferNbSample = -1;
        return this;
    }

    private void setValues(DataBuffer data, int width, int height) {
        this.image = null;
        this.raster = null;
        this.buffer = data;
        this.bufferWidth = width;
        this.bufferHeight = height;
        this.bufferNbSample = 1;
    }

    /**
     * Sets the grid geometry to the given envelope.
     * This method creates a new {@link GridGeometry}
     * then invokes {@link #setDomain(GridGeometry)}.
     *
     * @param envelope The new grid geometry envelope, or {@code null}.
     */
    public GridCoverageBuilder2 setDomain(Envelope envelope) {
        return setDomain(envelope == null ? null : new GridGeometry(null, envelope));
    }

    /**
     * Sets the grid geometry to the given value.
     *
     * @param grid The new grid geometry, or {@code null}.
     */
    public GridCoverageBuilder2 setDomain(GridGeometry grid) {
        this.grid = grid;
        return this;
    }

    /**
     * Sets all sample dimensions.
     *
     * @param range The new sample dimensions, or {@code null}.
     */
    public GridCoverageBuilder2 setRanges(final SampleDimension... range) {
        this.ranges = (range == null) ? null : new ArrayList<>(Arrays.asList(range));
        return this;
    }

    /**
     * Sets all sample dimensions.
     *
     * @param range The new sample dimensions, or {@code null}.
     */
    public GridCoverageBuilder2 setRanges(Collection<? extends SampleDimension> range) {
        this.ranges = (range == null) ? null : new ArrayList<>(range);
        return this;
    }

    /**
     * When building coverage with a grid geometry without a grid to crs transform
     * the grid to crs is computed automaticaly.
     * The default behavior creates a grid geometry with increasing values on all
     * axis. This method allows to reverse direction on an axis.
     *
     * @param dimension
     */
    public GridCoverageBuilder2 flipAxis(int dimension) {
        ArgumentChecks.ensurePositive("idx", dimension);
        flippedAxis.add(dimension);
        return this;
    }

    /**
     * Creates the grid coverage.
     * Current implementation may create a {@link BufferedGridCoverage} or {@link GridCoverage2D},
     * but future implementations may instantiate different other coverage types.
     *
     * @return created coverage
     * @throws IllegalGridGeometryException if the {@code domain} does not met the above-documented conditions.
     * @throws IllegalArgumentException if the image number of bands is not the same than the number of sample dimensions.
     */
    public GridCoverage build() {

        GridGeometry grid = this.grid;
        List<SampleDimension> ranges = this.ranges;
        RenderedImage image = this.image;

        //create an image from raster
        if (raster != null) {
            final int dataType = raster.getSampleModel().getDataType();
            final int numBands = raster.getSampleModel().getNumBands();

            if (ranges == null) {
                ranges = new ArrayList<>(numBands);
                for (int i = 0; i < numBands; i++) {
                    ranges.add(new SampleDimension.Builder().setName(i).build());
                }
            }

            final ColorModel colors = ColorModelFactory.createColorModel(ranges.toArray(new SampleDimension[0]), 0, dataType, ColorModelFactory.GRAYSCALE);
            image = new BufferedImage(colors, raster, false, null);
        }

        if (image != null) {
            grid = addExtentIfAbsent(grid, image.getWidth(), image.getHeight(), flippedAxis);
            //use provided ranges, even if null, GridCoverage2D makes a better work at building them
            return new GridCoverage2D(grid, this.ranges, image);
        } else if (buffer != null) {

            //verify and enrich grid geometry
            if (bufferWidth != -1) {
                if (grid.isDefined(GridGeometry.EXTENT)) {
                    GridExtent extent = grid.getExtent();
                    if (extent.getDimension() != 2) {
                        throw new IllegalGridGeometryException("Grid dimension differ from buffer size, expected 2 found " + extent.getDimension());
                    } else if (extent.getSize(0) != bufferWidth) {
                        throw new IllegalGridGeometryException("Grid width differ from buffer width, expected " + bufferWidth + " found " + extent.getSize(0));
                    } else if (extent.getSize(1) != bufferHeight) {
                        throw new IllegalGridGeometryException("Grid height differ from buffer height, expected " + bufferHeight + " found " + extent.getSize(1));
                    }
                } else {
                    grid = addExtentIfAbsent(grid, bufferWidth, bufferHeight, flippedAxis);
                }
            }
            //verify sample dimensions
            if (bufferNbSample != -1) {
                if (ranges != null && ranges.size() != bufferNbSample) {
                    throw new IllegalArgumentException("Sample dimension list differ from matrix, expected " + bufferNbSample + " found " + ranges.size());
                }
                if (ranges == null) {
                    //create default dimensions
                    ranges = new ArrayList<>(bufferNbSample);
                    for (int i = 0; i < bufferNbSample; i++) {
                        ranges.add(new SampleDimension.Builder().setName(i).build());
                    }
                }
            }

            return new BufferedGridCoverage(grid, ranges, buffer);
        } else {
            throw new IllegalArgumentException("Image, buffer or matrix must be set before building coverage.");
        }
    }

    /**
     * If the given domain does not have a {@link GridExtent}, creates a new grid geometry
     * with an extent of given size.
     */
    private static GridGeometry addExtentIfAbsent(GridGeometry domain, int width, int height, Set<Integer> flippedAxis) {
        if (domain == null) {
            GridExtent extent = new GridExtent(width, height);
            domain = new GridGeometry(extent, PixelInCell.CELL_CENTER, null, null);
        } else if (!domain.isDefined(GridGeometry.EXTENT)) {
            final int dimension = domain.getDimension();
            if (dimension >= 2) {
                CoordinateReferenceSystem crs = null;
                if (domain.isDefined(GridGeometry.CRS)) {
                    crs = domain.getCoordinateReferenceSystem();
                }
                final long[] low  = new long[dimension];
                final long[] high = new long[dimension];
                high[0] = width - 1;        // Inclusive.
                high[1] = height - 1;
                DimensionNameType[] axisTypes = GridExtent.typeFromAxes(crs, dimension);
                if (axisTypes == null) {
                    axisTypes = new DimensionNameType[dimension];
                }
                if (!ArraysExt.contains(axisTypes, DimensionNameType.COLUMN)) axisTypes[0] = DimensionNameType.COLUMN;
                if (!ArraysExt.contains(axisTypes, DimensionNameType.ROW))    axisTypes[1] = DimensionNameType.ROW;
                final GridExtent extent = new GridExtent(axisTypes, low, high, true);
                if (domain.isDefined(GridGeometry.GRID_TO_CRS)) {
                    try {
                        domain = new GridGeometry(domain, extent, null);
                    } catch (TransformException e) {
                        throw new IllegalGridGeometryException(e);                  // Should never happen.
                    }
                } else if (flippedAxis.isEmpty()) {
                    domain = new GridGeometry(extent, domain.envelope);
                } else {
                    // create transform with flipped axis
                    boolean nilEnvelope = true;
                    final ImmutableEnvelope env = ImmutableEnvelope.castOrCopy(domain.envelope);
                    if (env == null || ((nilEnvelope = env.isAllNaN()) && env.getCoordinateReferenceSystem() == null)) {
                        //do nothing
                    } else if (!nilEnvelope) {
                        /*
                         * If we have both the extent and an envelope with at least one non-NaN coordinates,
                         * create the `cornerToCRS` transform. The `gridToCRS` calculation uses the knowledge
                         * that all scale factors are on diagonal with no sign reversal, which allows simpler
                         * calculation than full matrix multiplication. Use double-double arithmetic everywhere.
                         */
                        final MatrixSIS affine = extent.cornerToCRS(env);
                        final MathTransform cornerToCRS = MathTransforms.linear(affine);
                        final int srcDim = cornerToCRS.getSourceDimensions();       // Translation column in matrix.
                        final int tgtDim = cornerToCRS.getTargetDimensions();       // Number of matrix rows before last row.
                        for (int j=0; j<tgtDim; j++) {
                            final DoubleDouble scale  = (DoubleDouble) affine.getNumber(j, j);
                            final DoubleDouble offset = (DoubleDouble) affine.getNumber(j, srcDim);
                            scale.multiply(0.5);
                            offset.add(scale);
                            affine.setNumber(j, srcDim, offset);
                        }
                        MathTransform gridToCRS = MathTransforms.linear(affine);

                        //apply flipped axis
                        final MatrixSIS flip = Matrices.createDiagonal(affine.getNumRow(), affine.getNumCol());
                        for (Integer i : flippedAxis) {
                            flip.setElement(i, i, -1);
                            flip.setElement(i, srcDim, extent.getSize(i, true));
                        }

                        gridToCRS = MathTransforms.concatenate(MathTransforms.linear(flip), gridToCRS);

                        domain = new GridGeometry(extent, PixelInCell.CELL_CENTER, gridToCRS, env.getCoordinateReferenceSystem());
                    }
                }
            }
        }
        return domain;
    }
}
