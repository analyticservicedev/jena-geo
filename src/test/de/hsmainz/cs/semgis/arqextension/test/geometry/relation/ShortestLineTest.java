package de.hsmainz.cs.semgis.arqextension.test.geometry.relation;

import static org.junit.Assert.assertEquals;

import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.Test;

import de.hsmainz.cs.semgis.arqextension.geometry.relation.ShortestLine;
import io.github.galbiston.geosparql_jena.implementation.datatype.WKTDatatype;

public class ShortestLineTest {

	public static final String testGeom="LINESTRING(77.29 29.07,77.42 29.26,77.27 29.31,77.29 29.07)";
	
	public static final String testGeom2="LINESTRING(5 5 ,10 10)";
	
	public static final String result="LINESTRING(77.29 29.07, 10 10)";
	
	@Test
	public void testReverseRing() {
        NodeValue geometryLiteral = NodeValue.makeNode(testGeom, WKTDatatype.INSTANCE);
        NodeValue geometryLiteral2 = NodeValue.makeNode(testGeom2, WKTDatatype.INSTANCE);
        ShortestLine instance=new ShortestLine();
        NodeValue expResult = NodeValue.makeNode(result, WKTDatatype.INSTANCE);
        NodeValue result = instance.exec(geometryLiteral,geometryLiteral2);
        assertEquals(expResult, result);
	}
	
}
