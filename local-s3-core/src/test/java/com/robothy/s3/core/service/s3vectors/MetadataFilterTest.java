package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for MetadataFilter implementation.
 * Tests various S3 Vectors metadata filtering operators according to AWS specification.
 */
class MetadataFilterTest {

    private ObjectMapper objectMapper;
    private List<VectorObjectMetadata> testVectors;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        testVectors = createTestVectors();
    }

    private List<VectorObjectMetadata> createTestVectors() {
        // Vector 1: Documentary, year 2020, genres array
        ObjectNode metadata1 = objectMapper.createObjectNode();
        metadata1.put("genre", "documentary");
        metadata1.put("year", 2020);
        metadata1.set("categories", objectMapper.createArrayNode().add("film").add("educational"));
        metadata1.put("rating", 8.5);
        metadata1.put("available", true);
        VectorObjectMetadata vector1 = new VectorObjectMetadata("vector1", 3, 1L, metadata1);

        // Vector 2: Drama, year 2019, no categories
        ObjectNode metadata2 = objectMapper.createObjectNode();
        metadata2.put("genre", "drama");
        metadata2.put("year", 2019);
        metadata2.put("rating", 7.8);
        metadata2.put("available", false);
        VectorObjectMetadata vector2 = new VectorObjectMetadata("vector2", 3, 2L, metadata2);

        // Vector 3: Comedy, year 2021, genres array including documentary
        ObjectNode metadata3 = objectMapper.createObjectNode();
        metadata3.put("genre", "comedy");
        metadata3.put("year", 2021);
        metadata3.set("categories", objectMapper.createArrayNode().add("documentary").add("humor"));
        metadata3.put("rating", 6.2);
        metadata3.put("available", true);
        VectorObjectMetadata vector3 = new VectorObjectMetadata("vector3", 3, 3L, metadata3);

        // Vector 4: No metadata (null)
        VectorObjectMetadata vector4 = new VectorObjectMetadata("vector4", 3, 4L, null);

        return Arrays.asList(vector1, vector2, vector3, vector4);
    }

    @Test
    void testNullOrEmptyFilter() {
        // Null filter should return all vectors
        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(null));
        assertEquals(4, result.size());

        // Empty object filter should return all vectors
        ObjectNode emptyFilter = objectMapper.createObjectNode();
        result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(emptyFilter));
        assertEquals(4, result.size());
    }

    @Test
    void testSimpleEquality() throws Exception {
        // Test implicit $eq (documentary)
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("genre", "documentary");

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector1", result.get(0).getVectorId());
    }

    @Test
    void testExplicitEquality() throws Exception {
        // Test explicit $eq
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode genreFilter = objectMapper.createObjectNode();
        genreFilter.put("$eq", "drama");
        filter.set("genre", genreFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector2", result.get(0).getVectorId());
    }

    @Test
    void testNotEqual() throws Exception {
        // Test $ne (not equal to documentary)
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode genreFilter = objectMapper.createObjectNode();
        genreFilter.put("$ne", "documentary");
        filter.set("genre", genreFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size()); // drama and comedy, but not the one with null metadata
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector2")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector3")));
    }

    @Test
    void testNumericComparisons() throws Exception {
        // Test $gt (year > 2019)
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode yearFilter = objectMapper.createObjectNode();
        yearFilter.put("$gt", 2019);
        filter.set("year", yearFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size()); // 2020 and 2021
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector1")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector3")));

        // Test $lte (rating <= 7.8)
        filter = objectMapper.createObjectNode();
        ObjectNode ratingFilter = objectMapper.createObjectNode();
        ratingFilter.put("$lte", 7.8);
        filter.set("rating", ratingFilter);

        result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size()); // drama (7.8) and comedy (6.2)
    }

    @Test
    void testInOperator() throws Exception {
        // Test $in
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode genreFilter = objectMapper.createObjectNode();
        ArrayNode genreArray = objectMapper.createArrayNode();
        genreArray.add("comedy");
        genreArray.add("documentary");
        genreFilter.set("$in", genreArray);
        filter.set("genre", genreFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector1")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector3")));
    }

    @Test
    void testNotInOperator() throws Exception {
        // Test $nin
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode genreFilter = objectMapper.createObjectNode();
        ArrayNode genreArray = objectMapper.createArrayNode();
        genreArray.add("comedy");
        genreArray.add("documentary");
        genreFilter.set("$nin", genreArray);
        filter.set("genre", genreFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector2", result.get(0).getVectorId()); // only drama
    }

    @Test
    void testExistsOperator() throws Exception {
        // Test $exists true
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode categoriesFilter = objectMapper.createObjectNode();
        categoriesFilter.put("$exists", true);
        filter.set("categories", categoriesFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size()); // vector1 and vector3 have categories
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector1")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector3")));

        // Test $exists false
        categoriesFilter.put("$exists", false);
        result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size()); // vector2 and vector4 don't have categories
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector2")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector4")));
    }

    @Test
    void testArrayEquality() throws Exception {
        // Test equality with array field - should match if any element equals target
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("categories", "documentary");

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector3", result.get(0).getVectorId()); // has documentary in categories array
    }

    @Test
    void testAndOperator() throws Exception {
        // Test $and: genre = documentary AND year >= 2020
        ObjectNode filter = objectMapper.createObjectNode();
        ArrayNode andArray = objectMapper.createArrayNode();

        ObjectNode genreCondition = objectMapper.createObjectNode();
        genreCondition.put("genre", "documentary");
        andArray.add(genreCondition);

        ObjectNode yearCondition = objectMapper.createObjectNode();
        ObjectNode yearFilter = objectMapper.createObjectNode();
        yearFilter.put("$gte", 2020);
        yearCondition.set("year", yearFilter);
        andArray.add(yearCondition);

        filter.set("$and", andArray);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector1", result.get(0).getVectorId());
    }

    @Test
    void testOrOperator() throws Exception {
        // Test $or: genre = documentary OR year = 2021
        ObjectNode filter = objectMapper.createObjectNode();
        ArrayNode orArray = objectMapper.createArrayNode();

        ObjectNode genreCondition = objectMapper.createObjectNode();
        genreCondition.put("genre", "documentary");
        orArray.add(genreCondition);

        ObjectNode yearCondition = objectMapper.createObjectNode();
        yearCondition.put("year", 2021);
        orArray.add(yearCondition);

        filter.set("$or", orArray);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size()); // vector1 (documentary) and vector3 (2021)
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector1")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector3")));
    }

    @Test
    void testLogicalOperatorWithFieldConditions() throws Exception {
        // genre = comedy AND (year = 2020 OR year = 2021): the field condition must not be dropped
        JsonNode filter = objectMapper.readTree(
            "{\"genre\":\"comedy\",\"$or\":[{\"year\":2020},{\"year\":2021}]}");
        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector3", result.get(0).getVectorId());

        // $and and $or side by side: both apply
        filter = objectMapper.readTree(
            "{\"$and\":[{\"available\":true}],\"$or\":[{\"year\":2019},{\"year\":2020}]}");
        result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector1", result.get(0).getVectorId());
    }

    @Test
    void testMultipleConditionsOnSameField() throws Exception {
        // Test price range: rating >= 7.0 AND rating <= 8.0
        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode ratingFilter = objectMapper.createObjectNode();
        ratingFilter.put("$gte", 7.0);
        ratingFilter.put("$lte", 8.0);
        filter.set("rating", ratingFilter);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(1, result.size());
        assertEquals("vector2", result.get(0).getVectorId()); // rating 7.8
    }

    @Test
    void testBooleanFiltering() throws Exception {
        // Test boolean equality
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("available", true);

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector1")));
        assertTrue(result.stream().anyMatch(v -> v.getVectorId().equals("vector3")));
    }

    @Test
    void testInvalidFilters() {
        // Test invalid $and (not an array)
        ObjectNode filter1 = objectMapper.createObjectNode();
        filter1.put("$and", "invalid");

        assertThrows(LocalS3VectorException.class, () -> 
            MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter1)));

        // Test invalid $or (empty array)
        ObjectNode filter2 = objectMapper.createObjectNode();
        filter2.set("$or", objectMapper.createArrayNode());

        assertThrows(LocalS3VectorException.class, () -> 
            MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter2)));

        // Test invalid $in (not an array)
        ObjectNode filter3 = objectMapper.createObjectNode();
        ObjectNode genreFilter = objectMapper.createObjectNode();
        genreFilter.put("$in", "invalid");
        filter3.set("genre", genreFilter);

        assertThrows(LocalS3VectorException.class, () -> 
            MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter3)));

        // Test invalid $exists (not boolean)
        ObjectNode filter4 = objectMapper.createObjectNode();
        ObjectNode existsFilter = objectMapper.createObjectNode();
        existsFilter.put("$exists", "invalid");
        filter4.set("genre", existsFilter);

        assertThrows(LocalS3VectorException.class, () -> 
            MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter4)));

        // Test unsupported operator
        ObjectNode filter5 = objectMapper.createObjectNode();
        ObjectNode invalidFilter = objectMapper.createObjectNode();
        invalidFilter.put("$invalid", "value");
        filter5.set("genre", invalidFilter);

        assertThrows(LocalS3VectorException.class, () -> 
            MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter5)));
    }

    @Test
    void testInvalidFilterIsRejectedWhenRead() throws Exception {
        // Rejected by fromJson itself, before any vector is compared: the outcome doesn't depend on the data
        for (String filter : List.of("\"abc\"", "[]", "1", "{\"a\":{\"$foo\":1}}", "{\"$foo\":1}",
            "{\"a\":{}}", "{\"a\":[1]}", "{\"a\":{\"$eq\":[1]}}", "{\"a\":{\"$in\":[[1]]}}",
            "{\"a\":{\"$nin\":[]}}", "{\"$and\":[]}", "{\"$or\":[\"abc\"]}",
            // The invalid condition comes after one that no vector matches, where $and short-circuits
            "{\"$and\":[{\"genre\":\"none\"},{\"a\":{\"$foo\":1}}]}",
            "{\"$or\":[{\"genre\":\"drama\"},{\"a\":{\"$exists\":\"yes\"}}]}")) {
            JsonNode node = objectMapper.readTree(filter);
            assertThrows(LocalS3VectorException.class, () -> MetadataFilterExpression.fromJson(node), filter);
        }
    }

    @Test
    void testNumericComparisonWithNonNumericField() throws Exception {
        // A field that isn't numeric only makes its vector miss
        JsonNode filter = objectMapper.readTree("{\"genre\":{\"$gt\":5}}");
        assertTrue(MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter)).isEmpty());

        // One vector whose year is the string "2019" doesn't fail the query of the others
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("year", "2019");
        List<VectorObjectMetadata> vectors = new java.util.ArrayList<>(testVectors);
        vectors.add(new VectorObjectMetadata("vector5", 3, 5L, metadata));
        for (String operator : List.of("$gt", "$gte", "$lt", "$lte")) {
            filter = objectMapper.readTree("{\"year\":{\"" + operator + "\":2020}}");
            List<VectorObjectMetadata> result = MetadataFilter.applyFilter(vectors, MetadataFilterExpression.fromJson(filter));
            assertFalse(result.isEmpty(), operator);
            assertFalse(result.stream().anyMatch(v -> v.getVectorId().equals("vector5")), operator);
        }
    }

    @Test
    void testNumericComparisonWithNonNumericTarget() throws Exception {
        // A target that isn't numeric is a malformed request, whatever the field holds
        for (String filter : List.of("{\"year\":{\"$gt\":\"2000\"}}", "{\"genre\":{\"$lte\":\"x\"}}",
            "{\"missing\":{\"$lt\":null}}")) {
            JsonNode node = objectMapper.readTree(filter);
            assertThrows(LocalS3VectorException.class, () ->
                MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(node)), filter);
        }
    }

    @Test
    void testEmptyMetadata() throws Exception {
        // Test filter on vector with null metadata
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("genre", "documentary");

        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors, MetadataFilterExpression.fromJson(filter));
        // Should not include vector4 which has null metadata
        assertFalse(result.stream().anyMatch(v -> v.getVectorId().equals("vector4")));
    }

    @Test
    void testNonFilterableKeysAreTreatedAsAbsent() throws Exception {
        List<String> nonFilterable = List.of("genre");

        JsonNode filter = objectMapper.readTree("{\"genre\":\"documentary\"}");
        assertTrue(MetadataFilter.applyFilter(testVectors,
            MetadataFilterExpression.fromJson(filter).withNonFilterableKeys(nonFilterable)).isEmpty());

        filter = objectMapper.readTree("{\"genre\":{\"$exists\":false}}");
        assertEquals(4, MetadataFilter.applyFilter(testVectors,
            MetadataFilterExpression.fromJson(filter).withNonFilterableKeys(nonFilterable)).size());

        // Inside $or, and next to a filterable key
        filter = objectMapper.readTree("{\"$or\":[{\"genre\":\"drama\"},{\"year\":2021}]}");
        List<VectorObjectMetadata> result = MetadataFilter.applyFilter(testVectors,
            MetadataFilterExpression.fromJson(filter).withNonFilterableKeys(nonFilterable));
        assertEquals(List.of("vector3"), result.stream().map(VectorObjectMetadata::getVectorId).toList());

        // Without the setting the same filter matches as before
        filter = objectMapper.readTree("{\"genre\":\"documentary\"}");
        assertEquals(1, MetadataFilter.applyFilter(testVectors,
            MetadataFilterExpression.fromJson(filter).withNonFilterableKeys(null)).size());
    }
}
