package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorBucketAssertions;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.datatypes.s3vectors.ListOutputVector;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import com.robothy.s3.datatypes.s3vectors.response.ListVectorsResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.NavigableMap;

/**
 * See {@code docs/apis.md#supported-amazon-s3-vectors-apis} for how a listing continues and how vectors are segmented.
 */
public interface ListVectorsService extends S3VectorsMetadataAware, S3VectorsStorageAware {

    /**
     * Marks the tokens of this format, so that a token of another format, e.g. the offsets that ListVectors used to
     * answer with, is rejected rather than decoded into a key that would silently list from the wrong place.
     */
    String NEXT_TOKEN_PREFIX = "v1:";

    /**
     * List vectors under the read lock of the vector bucket, for the same reason as
     * {@linkplain GetVectorsService#getVectors}.
     */
    default ListVectorsResponse listVectors(String vectorBucketName, String indexName,
                                           Integer maxResults, String nextToken, Boolean returnData,
                                           Boolean returnMetadata, Integer segmentCount, Integer segmentIndex) {
        return withBucketReadLock(vectorBucketName, () -> {
            VectorBucketMetadata bucketMetadata = VectorBucketAssertions.assertVectorBucketExists(this, vectorBucketName);
            VectorIndexMetadata indexMetadata = VectorIndexAssertions.assertVectorIndexExists(bucketMetadata, indexName);

            int maxResultsValue = validateAndSetMaxResults(maxResults);
            validateSegmentParameters(segmentCount, segmentIndex);

            String lastKey = parseNextToken(nextToken);
            NavigableMap<String, VectorObjectMetadata> vectors = indexMetadata.getVectorObjects();
            NavigableMap<String, VectorObjectMetadata> remaining = lastKey == null ? vectors : vectors.tailMap(lastKey, false);

            List<VectorObjectMetadata> pageVectors = new ArrayList<>();
            boolean truncated = false;
            for (VectorObjectMetadata vector : remaining.values()) {
                if (!inSegment(vector.getVectorId(), segmentCount, segmentIndex)) {
                    continue;
                }
                if (pageVectors.size() == maxResultsValue) {
                    truncated = true;
                    break;
                }
                pageVectors.add(vector);
            }

            List<ListOutputVector> outputVectors = buildOutputVectors(pageVectors, returnData, returnMetadata);
            String responseNextToken = truncated ? encodeNextToken(pageVectors.getLast().getVectorId()) : null;

            return new ListVectorsResponse(outputVectors, responseNextToken);
        });
    }

    private int validateAndSetMaxResults(Integer maxResults) {
        return (maxResults != null && maxResults > 0) ? Math.min(maxResults, 1000) : 500;
    }

    private void validateSegmentParameters(Integer segmentCount, Integer segmentIndex) {
        if (segmentCount != null || segmentIndex != null) {
            if (segmentCount == null || segmentIndex == null) {
                throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
                    "Both segmentCount and segmentIndex must be provided together");
            }
            if (segmentCount < 1 || segmentCount > 16) {
                throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
                    "segmentCount must be between 1 and 16");
            }
            if (segmentIndex < 0 || segmentIndex >= segmentCount) {
                throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
                    "segmentIndex must be between 0 and segmentCount-1");
            }
        }
    }

    /**
     * {@link String#hashCode()} is specified by the JLS, so a key stays in its segment across restarts and JVMs.
     */
    private boolean inSegment(String vectorId, Integer segmentCount, Integer segmentIndex) {
        return segmentCount == null || Math.floorMod(vectorId.hashCode(), segmentCount) == segmentIndex;
    }

    private String encodeNextToken(String lastKey) {
        return Base64.getEncoder().encodeToString((NEXT_TOKEN_PREFIX + lastKey).getBytes(StandardCharsets.UTF_8));
    }

    private String parseNextToken(String nextToken) {
        if (nextToken == null || nextToken.trim().isEmpty()) {
            return null;
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            throw invalidNextToken();
        }
        if (!decoded.startsWith(NEXT_TOKEN_PREFIX)) {
            throw invalidNextToken();
        }
        return decoded.substring(NEXT_TOKEN_PREFIX.length());
    }

    private LocalS3VectorException invalidNextToken() {
        return new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION, "Invalid nextToken");
    }

    private List<ListOutputVector> buildOutputVectors(List<VectorObjectMetadata> vectors,
                                                     Boolean returnData, Boolean returnMetadata) {
        List<ListOutputVector> outputVectors = new ArrayList<>();

        for (VectorObjectMetadata vectorMetadata : vectors) {
            ListOutputVector outputVector = createOutputVector(vectorMetadata, returnData, returnMetadata);
            outputVectors.add(outputVector);
        }

        return outputVectors;
    }

    private ListOutputVector createOutputVector(VectorObjectMetadata vectorMetadata,
                                               Boolean returnData, Boolean returnMetadata) {
        ListOutputVector.ListOutputVectorBuilder builder = ListOutputVector.builder()
            .key(vectorMetadata.getVectorId());

        if (shouldIncludeData(returnData)) {
            addVectorData(builder, vectorMetadata);
        }

        if (shouldIncludeMetadata(returnMetadata)) {
            builder.metadata(vectorMetadata.getMetadata());
        }

        return builder.build();
    }

    private boolean shouldIncludeData(Boolean returnData) {
        return Boolean.TRUE.equals(returnData);
    }

    private boolean shouldIncludeMetadata(Boolean returnMetadata) {
        return Boolean.TRUE.equals(returnMetadata);
    }

    private void addVectorData(ListOutputVector.ListOutputVectorBuilder builder, VectorObjectMetadata vectorMetadata) {
        float[] vectorData;
        try {
            vectorData = vectorStorage().getVectorData(vectorMetadata.getStorageId());
        } catch (RuntimeException e) {
            // A vector without data would read as if returnData weren't set.
            throw new LocalS3VectorException(LocalS3VectorErrorType.INTERNAL_SERVER_ERROR,
                "Failed to read the data of vector " + vectorMetadata.getVectorId(), e);
        }
        if (vectorData == null) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INTERNAL_SERVER_ERROR,
                "The data of vector " + vectorMetadata.getVectorId() + " is missing");
        }
        builder.data(PutInputVector.VectorData.builder().values(vectorData).build());
    }
}
