package de.hsmainz.cs.semgis.arqextension.raster.attribute;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;
import org.apache.sis.coverage.grid.GridCoverage;
import org.opengis.coverage.CannotEvaluateException;

import io.github.galbiston.geosparql_jena.implementation.datatype.raster.CoverageWrapper;

/**
 * Returns the amount of space (in bytes) the raster takes.
 *
 */
public class MemSize extends FunctionBase1 {


	@Override
	public NodeValue exec(NodeValue v) {
		CoverageWrapper wrapper=CoverageWrapper.extract(v);
		GridCoverage raster=wrapper.getXYGeometry();	
        try {
			return NodeValue.makeInteger(raster.render(null).getData().getDataBuffer().getSize());
		} catch (CannotEvaluateException e) {
			return null;
		}
	}

}
