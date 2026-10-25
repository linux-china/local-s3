package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Implements metadata filtering for S3 Vector queries according to AWS S3 Vectors specification.
 * Supports various operators like $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin, $exists, $and, $or.
 * 
 * Filter examples:
 * - Simple equality: {"genre": "documentary"} or {"genre": {"$eq": "documentary"}}
 * - Numeric comparison: {"year": {"$gt": 2019}}
 * - Array operations: {"genre": {"$in": ["comedy", "documentary"]}}
 * - Logical operations: {"$and": [{"genre": {"$eq": "drama"}}, {"year": {"$gte": 2020}}]}
 */
public class MetadataFilter {

    private static final Logger log = LoggerFactory.getLogger(MetadataFilter.class);

    /**
     * Apply a metadata condition to a list of vector candidates.
     *
     * @param candidateVectors List of vectors to filter
     * @param filter the condition to keep a vector by; {@linkplain MetadataFilterExpression#none()} keeps
     *     every one of them
     * @return Filtered list of vectors that match the criteria
     */
    public static List<VectorObjectMetadata> applyFilter(List<VectorObjectMetadata> candidateVectors,
                                                         MetadataFilterExpression filter) {
        if (filter == null || filter.isEmpty()) {
            return candidateVectors;
        }

        List<VectorObjectMetadata> result = new ArrayList<>();
        for (VectorObjectMetadata vector : candidateVectors) {
            if (matchesFilter(vector, filter.json(), filter.nonFilterableKeys())) {
                result.add(vector);
            }
        }

        return result;
    }

    /**
     * Whether one vector satisfies a condition, which {@linkplain MetadataFilterExpression#matches} calls.
     *
     * @param vector the vector to evaluate the condition against
     * @param filter the condition, as the document it arrived as
     * @param nonFilterableKeys the keys to treat as absent from the metadata of the vector
     * @return true if the metadata of the vector satisfies the condition
     */
    static boolean matches(VectorObjectMetadata vector, JsonNode filter, Set<String> nonFilterableKeys) {
        return matchesFilter(vector, filter, nonFilterableKeys);
    }

    /**
     * Check the whole structure of a condition, so that whether a request is rejected depends on the request alone,
     * not on which vectors the index holds or on where an {@code $and} or {@code $or} short-circuits. Evaluation then
     * only matches.
     *
     * @param filter the condition, as the document it arrived as
     * @throws LocalS3VectorException of {@linkplain LocalS3VectorErrorType#INVALID_REQUEST} if it isn't a valid one
     */
    static void validate(JsonNode filter) {
        if (!filter.isObject()) {
            throw invalid("Filter must be a JSON object");
        }
        for (String key : filter.propertyNames()) {
            JsonNode condition = filter.get(key);
            switch (key) {
                case "$and", "$or" -> {
                    if (!condition.isArray() || condition.isEmpty()) {
                        throw invalid(key + " operator requires a non-empty array of filters");
                    }
                    for (JsonNode subFilter : condition) {
                        validate(subFilter);
                    }
                }
                default -> {
                    if (key.startsWith("$")) {
                        throw invalid("Unsupported filter operator: " + key);
                    }
                    validateFieldFilter(key, condition);
                }
            }
        }
    }

    private static void validateFieldFilter(String fieldName, JsonNode fieldFilter) {
        if (fieldFilter.isArray()) {
            throw invalid("The filter of the field '" + fieldName + "' must be a value or an object of operators");
        }
        if (!fieldFilter.isObject()) {
            return;
        }
        if (fieldFilter.isEmpty()) {
            throw invalid("The filter of the field '" + fieldName + "' has no operator");
        }
        for (String operator : fieldFilter.propertyNames()) {
            JsonNode operand = fieldFilter.get(operator);
            switch (operator) {
                case "$eq", "$ne" -> {
                    if (operand.isContainer()) {
                        throw invalid(operator + " operator requires a single value");
                    }
                }
                case "$gt", "$gte", "$lt", "$lte" -> {
                    if (!operand.isNumber()) {
                        throw invalid("Numeric comparison requires a numeric target value");
                    }
                }
                case "$in", "$nin" -> {
                    if (!operand.isArray() || operand.isEmpty()) {
                        throw invalid(operator + " operator requires a non-empty array value");
                    }
                    for (JsonNode element : operand) {
                        if (element.isContainer()) {
                            throw invalid(operator + " operator requires an array of single values");
                        }
                    }
                }
                case "$exists" -> {
                    if (!operand.isBoolean()) {
                        throw invalid("$exists operator requires a boolean value");
                    }
                }
                default -> throw invalid("Unsupported filter operator: " + operator);
            }
        }
    }

    private static LocalS3VectorException invalid(String message) {
        return new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, message);
    }

    /**
     * Check if a vector matches the given filter criteria, which {@linkplain #validate} has checked.
     * 
     * @param vector Vector to check
     * @param filter Filter criteria
     * @return true if vector matches the filter
     */
    private static boolean matchesFilter(VectorObjectMetadata vector, JsonNode filter, Set<String> nonFilterableKeys) {
        ObjectNode filterObj = (ObjectNode) filter;

        // Every key, a logical operator included, is one more condition of an implicit $and, so none is dropped
        for (String key : filterObj.propertyNames()) {
            JsonNode condition = filterObj.get(key);
            boolean matched = switch (key) {
                case "$and" -> evaluateAndOperator(vector, condition, nonFilterableKeys);
                case "$or" -> evaluateOrOperator(vector, condition, nonFilterableKeys);
                default -> evaluateFieldFilter(vector, key, condition, nonFilterableKeys);
            };
            if (!matched) {
                return false;
            }
        }

        return true;
    }

    /**
     * Evaluate $and logical operator.
     */
    private static boolean evaluateAndOperator(VectorObjectMetadata vector, JsonNode andNode,
                                               Set<String> nonFilterableKeys) {
        for (JsonNode subFilter : andNode) {
            if (!matchesFilter(vector, subFilter, nonFilterableKeys)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate $or logical operator.
     */
    private static boolean evaluateOrOperator(VectorObjectMetadata vector, JsonNode orNode,
                                              Set<String> nonFilterableKeys) {
        for (JsonNode subFilter : orNode) {
            if (matchesFilter(vector, subFilter, nonFilterableKeys)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluate filter for a specific field.
     */
    private static boolean evaluateFieldFilter(VectorObjectMetadata vector, String fieldName, JsonNode fieldFilter,
                                               Set<String> nonFilterableKeys) {
        JsonNode vectorMetadata = vector.getMetadata();
        // AWS doesn't index a non-filterable key, so a filter sees it as missing rather than matching its value
        JsonNode fieldValue = vectorMetadata != null && !nonFilterableKeys.contains(fieldName)
            ? vectorMetadata.get(fieldName) : null;

        // Handle simple equality (implicit $eq)
        if (!fieldFilter.isObject()) {
            return evaluateEquals(fieldValue, fieldFilter);
        }

        // Handle operator-based filters
        ObjectNode filterObj = (ObjectNode) fieldFilter;
        for (String operator : filterObj.propertyNames()) {
            JsonNode operatorValue = filterObj.get(operator);

            boolean operatorResult = switch (operator) {
                case "$eq" -> evaluateEquals(fieldValue, operatorValue);
                case "$ne" -> evaluateNotEquals(fieldValue, operatorValue);
                case "$gt" -> evaluateComparison(fieldValue, operatorValue, c -> c > 0);
                case "$gte" -> evaluateComparison(fieldValue, operatorValue, c -> c >= 0);
                case "$lt" -> evaluateComparison(fieldValue, operatorValue, c -> c < 0);
                case "$lte" -> evaluateComparison(fieldValue, operatorValue, c -> c <= 0);
                case "$in" -> evaluateIn(fieldValue, operatorValue);
                case "$nin" -> evaluateNotIn(fieldValue, operatorValue);
                default -> evaluateExists(fieldValue, operatorValue);
            };

            if (!operatorResult) {
                return false;
            }
        }

        return true;
    }

    /**
     * Evaluate equality comparison.
     * If the field value is an array, returns true if any element equals the target value.
     */
    private static boolean evaluateEquals(JsonNode fieldValue, JsonNode targetValue) {
        if (fieldValue == null || fieldValue.isNull()) {
            return targetValue == null || targetValue.isNull();
        }

        if (fieldValue.isArray()) {
            // For arrays, check if any element matches
            for (JsonNode arrayElement : fieldValue) {
                if (nodesEqual(arrayElement, targetValue)) {
                    return true;
                }
            }
            return false;
        }

        return nodesEqual(fieldValue, targetValue);
    }

    /**
     * Evaluate not equal comparison.
     * Returns true only if the field exists and is not equal to the target value.
     */
    private static boolean evaluateNotEquals(JsonNode fieldValue, JsonNode targetValue) {
        // If field doesn't exist, it doesn't match $ne condition
        if (fieldValue == null || fieldValue.isNull()) {
            return false;
        }

        return !evaluateEquals(fieldValue, targetValue);
    }

    /**
     * Evaluate $gt, $gte, $lt or $lte. A field that isn't numeric only makes this vector miss, as on AWS.
     */
    private static boolean evaluateComparison(JsonNode fieldValue, JsonNode targetValue, IntPredicate accepts) {
        if (fieldValue == null || !fieldValue.isNumber()) {
            return false;
        }
        return accepts.test(Double.compare(fieldValue.doubleValue(), targetValue.doubleValue()));
    }

    /**
     * Evaluate $in operator - check if field value is in the provided array.
     */
    private static boolean evaluateIn(JsonNode fieldValue, JsonNode arrayValue) {
        if (fieldValue == null || fieldValue.isNull()) {
            return false;
        }

        for (JsonNode targetElement : arrayValue) {
            if (evaluateEquals(fieldValue, targetElement)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Evaluate $nin operator - check if field value is not in the provided array.
     * Returns true only if the field exists and is not in the array.
     */
    private static boolean evaluateNotIn(JsonNode fieldValue, JsonNode arrayValue) {
        // If field doesn't exist, it doesn't match $nin condition
        if (fieldValue == null || fieldValue.isNull()) {
            return false;
        }

        return !evaluateIn(fieldValue, arrayValue);
    }

    /**
     * Evaluate $exists operator - check if field exists.
     */
    private static boolean evaluateExists(JsonNode fieldValue, JsonNode shouldExist) {
        boolean fieldExists = fieldValue != null && !fieldValue.isNull();
        return fieldExists == shouldExist.booleanValue();
    }

    /**
     * Check if two JsonNodes are equal, handling different numeric types.
     */
    private static boolean nodesEqual(JsonNode node1, JsonNode node2) {
        if (node1 == null && node2 == null) {
            return true;
        }
        if (node1 == null || node2 == null) {
            return false;
        }

        // Handle numeric comparisons
        if (node1.isNumber() && node2.isNumber()) {
            return Double.compare(node1.doubleValue(), node2.doubleValue()) == 0;
        }

        // Use Jackson's built-in equality for other types
        return node1.equals(node2);
    }
}
