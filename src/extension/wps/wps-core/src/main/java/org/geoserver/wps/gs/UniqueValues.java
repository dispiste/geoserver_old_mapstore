package org.geoserver.wps.gs;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.collections.ComparatorUtils;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geotools.data.Query;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.GeoTools;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.feature.visitor.UniqueVisitor;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.expression.Expression;

/**
 * A WPS process to retrieve unique field values from a layer on Geoserver catalog.
 * Requires a valid layer name and a field name to extract the unique values.
 * It accepts sorting and paging parameters.
 * 
 * @author Cesar Martinez Izquierdo
 *
 */
@DescribeProcess(title="UniqueValues", description="Gets the list of unique values for the given layer and field")
public class UniqueValues implements GeoServerProcess {

    /** The LOGGER. */
    private static final Logger LOGGER = Logging.getLogger(UniqueValues.class);

    private final Catalog catalog;

    public UniqueValues(Catalog catalog) {
        this.catalog = catalog;
    }


    @DescribeResult(name="result", description="output result")
    public SimpleFeatureCollection execute(
            @DescribeParameter(name = "layerName", min = 1, description = "Layer from which field values should be retrieved") String layerName,
            @DescribeParameter(name = "fieldName", min = 1, description = "Field from which the values should be retrieved") String fieldName,
            @DescribeParameter(name = "startIndex", min = 0, description = "The index of the first feature to retrieve") Integer startIndex,
            @DescribeParameter(name = "maxFeatures", min = 0, description = "The maximum numbers of features to fetch") Integer maxFeatures,
            @DescribeParameter(name = "sort", min = 0, description = "The sort order (ASC, DESC or NONE)") String sort
            ) throws IOException {

        // initial checks on mandatory params
        if (layerName == null || layerName.length() <= 0) {
            throw new IllegalArgumentException("Empty or null layerName provided!");
        }
        if (fieldName == null || fieldName.length() <= 0) {
            throw new IllegalArgumentException("Empty or null fieldName provided!");
        }
        LOGGER.fine("Download process called on resource: "+layerName+" - field: "+fieldName);

        //
        // Move on with the real code
        //
        // checking for the resources on the GeoServer c
        LayerInfo layerInfo = catalog.getLayerByName(layerName);
        if (layerInfo == null) {
            // could not find any layer ... abruptly interrupt the process
            throw new IllegalArgumentException("Unable to locate layer: " + layerName);

        }
        ResourceInfo resourceInfo = layerInfo.getResource();
        if (resourceInfo == null) {
            // could not find any data store associated to the specified layer ... abruptly interrupt the process
            throw new IllegalArgumentException("Unable to locate ResourceInfo for layer:"
                    + layerName);

        }
        LOGGER.log(Level.FINE,"The resource to work on is "+resourceInfo.getName());

        // CORE CODE
        // Followed strategy:
        // 1 - get the full feature collection
        // 2 - get the unique values of the collection (using UniqueVisitor)
        // 3 - sort them if required
        // 4 - get the desired range (startIndex, maxFeatures)
        // It would perform much better if the initial query could be hinted to get unique/distinct values,
        // but I have not found the way to do so
        if (resourceInfo instanceof FeatureTypeInfo) {
            LOGGER.log(Level.FINE,"The resource to work on is a vector layer");
            // get the feature collection
            FeatureTypeInfo featureType = (FeatureTypeInfo) resourceInfo;
            List<AttributeTypeInfo> attributes = featureType.attributes();
            checkField(fieldName, attributes);
            SimpleFeatureSource featureSource = (SimpleFeatureSource) featureType.getFeatureSource(null, GeoTools.getDefaultHints());
            String typeName = featureSource.getSchema().getGeometryDescriptor().getLocalName();
            Query query = new Query(typeName, Filter.INCLUDE, new String[] {fieldName});
            SimpleFeatureCollection featCol = featureSource.getFeatures(query);
            
            LOGGER.log(Level.FINE,"Getting unique values");
            // get the unique values
            FilterFactory factory = CommonFactoryFinder.getFilterFactory();
            Expression expr = factory.property(fieldName);
            UniqueVisitor visitor = new UniqueVisitor(expr);
            featCol.accepts(visitor, null);
            List uniqueValues = visitor.getResult().toList();
            
            LOGGER.log(Level.FINE,"Sorting and ordering result as requested");
            // sort if required
            sort(uniqueValues, sort);

            // set range
            List orderedRange = getSubRange(uniqueValues, startIndex, maxFeatures);

            // return it as ListFeatureCollection
            SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
            tb.add("value", featCol.getSchema().getDescriptor(0).getType().getBinding());
            tb.setName("UniqueValue");
            SimpleFeatureType ft = tb.buildFeatureType();
            SimpleFeatureBuilder fb = new SimpleFeatureBuilder(ft);

            ListFeatureCollection result = new ListFeatureCollection(ft);
            for (Object value : orderedRange) {
                fb.add(value);
                result.add(fb.buildFeature(null));
            }
            return result;
        }
        else {
            // wrong type
            throw new IllegalArgumentException(
                    "Could not complete the Download Process, requested layer is not vector layer");

        }
    }

    private void checkField(String fieldName, List<AttributeTypeInfo> attributes) {
        for (AttributeTypeInfo field: attributes) {
            if (fieldName.equals(field.getName())) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Could not complete the Process, requested field does not exist");
    }
    
    /**
     * Sorts a list according to the provided sort order. The list is sorted in place
     * (thus altering the order of the provided list).
     * 
     * @param values The list to be sorted
     * @param order Accepted values are "ASC", "DESC" and "NONE".
     * Any other value will be considered as "NONE".
     */
    private void sort(List values, String order){
        if (order!=null) {
            if (order.equals("ASC")) {
                Collections.sort(values, new ValueComparator());
            }
            else if (order.equals("DESC")) {
                Collections.sort(values, new Comparator<Object>() {
                	private ValueComparator comparator = new ValueComparator();
                    public int compare(Object o1, Object o2) {
                    	return comparator.compare(o2, o1);
                    }
                });
            }
        }
    }
    
    class ValueComparator implements Comparator {
		@Override
		public int compare(Object o1, Object o2) {
            if (o1!=null) {
                if (o2==null) {
                    return -1;
                }
                if (o1 instanceof String && o2 instanceof String) {
                    return ((String)o1).compareTo((String)o2);
                }
                if (o1 instanceof Integer && o2 instanceof Integer) {
                	return ((Integer)o1).compareTo((Integer)o2);
                }
                if (o1 instanceof Long && o2 instanceof Long) {
                	return ((Long)o1).compareTo((Long)o2);
                }
                if (o1 instanceof Short && o2 instanceof Short) {
                	return ((Short)o1).compareTo((Short)o2);
                }
                if (o1 instanceof Byte && o2 instanceof Byte) {
                	return ((Byte)o1).compareTo((Byte)o2);
                }
                if (o1 instanceof Double && o2 instanceof Double) {
                	return ((Double)o1).compareTo((Double)o2);
                }
                if (o1 instanceof Float && o2 instanceof Float) {
                	return ((Float)o1).compareTo((Float)o2);
                }
            }
            else if (o2!=null) {
                return 1;
            }
            return 0;
		}
    
    }
    
    private List getSubRange(List values, Integer startIndex, Integer maxFeatures) {
        int toIndex;
        if (startIndex!=null) {
            if (startIndex<0) {
                startIndex = 0;
            }
            else if (startIndex>values.size()) {
                startIndex = values.size();
            }
        }
        else {
            startIndex = 0;
        }
        if (maxFeatures!=null && maxFeatures>=0) {
            toIndex = startIndex + maxFeatures;
            if (toIndex>values.size()) {
                toIndex = values.size();
            }
        }
        else {
            toIndex = values.size();
        }
        return values.subList(startIndex, toIndex);

    }
}
