package org.n52.geoprocessing.wps.generator;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class UncertWebVissServiceUploader {

    private static Logger LOGGER = LoggerFactory.getLogger(UncertWebVissServiceUploader.class);

    private String vissServerHost;

    private Map<String, String> timeStampWMSURLMap = new HashMap<>();

    private StringWriter stringWriter;

    private JsonFactory f;
    
    private JsonGenerator g;
    
    private ObjectMapper m;

    private String createVisualizerURL;
    
    public UncertWebVissServiceUploader(String vissServerHost) {
        this.vissServerHost = vissServerHost;

        stringWriter = new StringWriter();

        f = new JsonFactory();
        try {
            g = f.createGenerator(stringWriter);
        } catch (IOException e) {
           LOGGER.error("Could not create JsonGenerator in constructor.", e);
        }

        m = new ObjectMapper();
    }

    public String sendRequest(String target,
            String request) throws HttpException, IOException {
        HttpClient client = new HttpClient();
        EntityEnclosingMethod requestMethod = null;

        requestMethod = new PostMethod(target);
        requestMethod.setRequestHeader("Content-type", "application/json");

        RequestEntity requestEntity = new StringRequestEntity(request, "application/json", "UTF-8");

        requestMethod.setRequestEntity(requestEntity);

        int statusCode = client.executeMethod(requestMethod);

        if (!((statusCode == HttpStatus.SC_OK) || (statusCode == HttpStatus.SC_CREATED))) {
            System.err.println("Method failed: " + requestMethod.getStatusLine());
        }

        // Read the response body.
        byte[] responseBody = requestMethod.getResponseBody();
        return new String(responseBody);
    }

    public String sendGETRequest(String request) throws HttpException, IOException {
        HttpClient client = new HttpClient();
        GetMethod requestMethod = new GetMethod(request);

        int statusCode = client.executeMethod(requestMethod);

        if (statusCode != HttpStatus.SC_OK) {
            System.err.println("Method failed: " + requestMethod.getStatusLine());
        }

        // Read the response body.
        byte[] responseBody = requestMethod.getResponseBody();
        return new String(responseBody);
    }

    public String createVissResource(String resourceURL) throws IOException {

        g.writeStartObject();
        g.writeStringField("url", resourceURL);
        g.writeStringField("responseMediaType", "application/x-netcdf");
        g.writeEndObject();
        g.close();

        String createResourceJson = stringWriter.toString();

        String createResourceResponse = sendRequest(vissServerHost, createResourceJson);

        JsonNode rootNode = m.readTree(createResourceResponse);

        JsonNode datasetNodes = rootNode.path("dataSets");

        JsonNode hrefNode = null;

        if (datasetNodes instanceof ArrayNode) {
            hrefNode = ((ArrayNode) datasetNodes).get(0).get("href");
        } else {
            hrefNode = datasetNodes.get("href");
        }

        String href = hrefNode.asText();

        LOGGER.info("URL of newly created resource: " + href);

        // get request to href to get time attributes
        String datasetInfo = sendGETRequest(href);

        JsonNode temporalExtentNode = m.readTree(datasetInfo).path("temporalExtent");

        String begin = extractStringValueFromNode(extractNode(temporalExtentNode, "begin", true));
        String end = extractStringValueFromNode(extractNode(temporalExtentNode, "end", true));
        String seperator = extractStringValueFromNode(extractNode(temporalExtentNode, "seperator", false));

        createVisualizerURL = href + "/visualizers/Distribution-Normal-Mean";

        if (seperator != null) {

            long seperatorAsLong = Long.parseLong(seperator);

            LOGGER.info("Begin: {}, end: {}, seperator: {}", begin, end, seperator);

            DateTime beginDateTime = DateTime.parse(begin);
            DateTime endDateTime = DateTime.parse(end);

            // start with begin datetime
            // increase by seperator until end datetime is reached
            while (!beginDateTime.equals(endDateTime)) {

                String timeStamp = beginDateTime.toString();

                createVisualizer(timeStamp);
                
                beginDateTime = beginDateTime.plus(seperatorAsLong);
            }
        } else {
            createStringListFromNode(extractNode(temporalExtentNode, "instants", false), "instant");
        }

        try {
            return createJsonFromHashMap();
        } catch (Exception e) {
            LOGGER.error("Could not create result JSON.", e);
        }
        return "{}";
    }

    private void createVisualizer(String timeStamp) throws IOException {
        
        StringWriter stringWriter = new StringWriter();
        g = f.createGenerator(stringWriter);

        g.writeStartObject();
        g.writeStringField("time", timeStamp);
        g.writeEndObject();
        g.close();
        LOGGER.info(stringWriter.toString());
        String createVisualizerResponse = sendRequest(createVisualizerURL, stringWriter.toString());
        try {
            addCreateVisualizerResultToHashmap(timeStamp, m.readTree(createVisualizerResponse));
        } catch (Exception e) {
            LOGGER.info("Exception occurred while trying to create visualization for timestamp: " + timeStamp);
        }

    }

    private void addCreateVisualizerResultToHashmap(String timeStamp,
            JsonNode responseNode) {

        JsonNode referenceNode = responseNode.path("reference");

        JsonNode urlNode = referenceNode.path("url");
        JsonNode layersNode = referenceNode.path("layers");

        String layerName = layersNode.asText();

        if (layersNode.isArray()) {
            layerName = layersNode.get(0).asText();
        }

        String wmsURL = urlNode.asText() + "?service=WMS&version=1.1.0&request=GetMap&layers=" + layerName
                + "&styles=&bbox=385735.1640999372,5666656.270399797,386213.9640999382,5667007.770399805&width=512&height=375&srs=EPSG:31466&format=image/png";
        timeStampWMSURLMap.put(timeStamp, wmsURL);
    }

    private String createJsonFromHashMap() throws Exception {
        return createJsonFromHashMap(timeStampWMSURLMap);
    }
    
    public String createJsonFromHashMap(Map<String, String> timeStampWMSURLMap) throws Exception {
        StringWriter stringWriter = new StringWriter();
        
        JsonFactory f = new JsonFactory();
        JsonGenerator g = f.createGenerator(stringWriter);
        
        g.writeStartObject();
        
        g.writeArrayFieldStart("values");
        
        for (String timestamp : timeStampWMSURLMap.keySet()) {
            g.writeStartObject();
            g.writeStringField("timestamp", convertToUNIXTime(timestamp));
            g.writeStringField("value", timeStampWMSURLMap.get(timestamp));
            g.writeEndObject();
        }
        
        g.writeEndArray();
        
        g.writeEndObject();
        g.close();
        return stringWriter.toString();
    }

    private void createStringListFromNode(JsonNode node,
            String string) throws IllegalArgumentException {

        if (!(node instanceof ArrayNode)) {
            throw new IllegalArgumentException(String.format("Node %s is not an array node.", node.toString()));
        }

        ArrayNode arrayNode = (ArrayNode) node;

        Iterator<JsonNode> nodeIterator = arrayNode.iterator();

        while (nodeIterator.hasNext()) {
            JsonNode jsonNode = (JsonNode) nodeIterator.next();

            String timeStamp = extractStringValueFromNode(extractNode(jsonNode, string, true));
            
            try {
                createVisualizer(timeStamp);
            } catch (IOException e) {
                LOGGER.error("Could not create visualizer for time stamp: " + timeStamp);
            }
        }

    }

    private String extractStringValueFromNode(JsonNode node) {
        if (node != null) {
            return node.asText();
        }
        return null;
    }

    private JsonNode extractNode(JsonNode node,
            String fieldName,
            boolean mandatory) throws IllegalArgumentException {

        JsonNode resultNode = node.get(fieldName);

        if (resultNode == null && mandatory) {
            throw new IllegalArgumentException(
                    String.format("Fieldname '%s' is mandatory but not found in node %s.", fieldName, node.toString()));
        }
        return resultNode;
    }
    
    public String convertToUNIXTime(String timeStamp){
        
        String result = "";
        
        Instant parsedDate = Instant.parse(timeStamp);
        
        result = parsedDate.toEpochMilli() + "";
        
        return result;
        
    }
    
    public String convertFromUNIXTime(String timeStamp){
        
        String result = "";
        
        Instant parsedDate = Instant.ofEpochMilli(Long.valueOf(timeStamp));
        
        result = parsedDate.toString();
        
        return result;
        
    }

}
