package de.hsmainz.cs.semgis.arqextension.raster.attribute;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;
import org.apache.sis.coverage.grid.GridCoverage;
import org.geotoolkit.image.ImageWorker;

import de.hsmainz.cs.semgis.arqextension.util.LiteralUtils;
import de.hsmainz.cs.semgis.arqextension.util.Wrapper;
import io.github.galbiston.geosparql_jena.implementation.CoverageWrapper;

public class IsIndexed extends FunctionBase1 {

	@Override
	public NodeValue exec(NodeValue v) {
		Wrapper wrapper1=LiteralUtils.rasterOrVector(v);
		GridCoverage raster=((CoverageWrapper)wrapper1).getXYGeometry();
	    ImageWorker worker=new ImageWorker(raster.getRenderedImage());
		return NodeValue.makeBoolean(worker.isIndexed());
	}

}
