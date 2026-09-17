package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorBucketAssertions;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import com.robothy.s3.datatypes.s3vectors.response.QueryVectorsResponse;
import com.robothy.s3.datatypes.s3vectors.response.QueryOutputVector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public interface QueryVectorsService extends S3VectorsMetadataAware, S3VectorsStorageAware {

    /**
     * The maximum {@code topK} of a query, see
     * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html">S3 Vectors limitations</a>.
     */
    int MAX_TOP_K = 10_000;

    /**
     * Query the nearest vectors of an index, under the read lock of the vector bucket, so that the vectors that are
     * compared aren't deleted meanwhile.
     *
     * @throws LocalS3VectorException of {@linkplain LocalS3VectorErrorType#INTERNAL_SERVER_ERROR} if the data of a
     *     vector of the index is missing or corrupt.
     */
    default QueryVectorsResponse queryVectors(String vectorBucketName, String indexName,
                                             PutInputVector.VectorData queryVector, Integer topK,
                                             Boolean returnDistance, Boolean returnMetadata,
                                             MetadataFilterExpression filter) {
        return withBucketReadLock(vectorBucketName, () -> {
            VectorBucketMetadata bucketMetadata = VectorBucketAssertions.assertVectorBucketExists(this, vectorBucketName);
            VectorIndexMetadata indexMetadata = VectorIndexAssertions.assertVectorIndexExists(bucketMetadata, indexName);
            float[] queryVectorData = validateQueryVector(queryVector, indexMetadata.getDimension());
            int validatedTopK = validateTopK(topK);

            Collection<VectorObjectMetadata> candidateVectors = indexMetadata.getVectorObjects().values();

            if (candidateVectors.isEmpty()) {
                return buildEmptyResponse(indexMetadata.getDistanceMetric());
            }

            List<VectorSearchEngine.VectorSearchResult> searchResults = performVectorSearch(
                queryVectorData, candidateVectors, indexMetadata, validatedTopK, filter);

            List<QueryOutputVector> outputVectors = buildOutputVectors(searchResults, returnDistance, returnMetadata);

            return buildResponse(outputVectors, indexMetadata.getDistanceMetric());
        });
    }

    private float[] validateQueryVector(PutInputVector.VectorData queryVector, int indexDimension) {
        if (queryVector == null || queryVector.getValues() == null) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, "Query vector is required");
        }

        float[] queryVectorData = queryVector.getValues();
        if (queryVectorData.length == 0) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, "Query vector data cannot be empty");
        }

        if (queryVectorData.length != indexDimension) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
                String.format("Query vector dimension %d does not match index dimension %d", 
                             queryVectorData.length, indexDimension));
        }

        return queryVectorData;
    }

    private int validateTopK(Integer topK) {
        if (topK == null || topK < 1) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, "topK must be at least 1");
        }
        if (topK > MAX_TOP_K) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
                "topK must be at most " + MAX_TOP_K);
        }
        return topK;
    }

    private QueryVectorsResponse buildEmptyResponse(DistanceMetric distanceMetric) {
        return QueryVectorsResponse.builder()
            .vectors(new ArrayList<>())
            .distanceMetric(distanceMetric)
            .build();
    }

    private List<VectorSearchEngine.VectorSearchResult> performVectorSearch(
            float[] queryVectorData, Collection<VectorObjectMetadata> candidateVectors,
            VectorIndexMetadata indexMetadata, int topK, MetadataFilterExpression filter) {
        try {
            return VectorSearchEngine.createBasic().findNearestVectors(
                queryVectorData,
                candidateVectors,
                vectorStorage(),
                indexMetadata.getDistanceMetric(),
                topK,
                filter
            );
        } catch (IllegalStateException e) {
            throw new LocalS3VectorException(LocalS3VectorErrorType.INTERNAL_SERVER_ERROR,
                "Failed to query the index '" + indexMetadata.getIndexName() + "': " + e.getMessage(), e);
        }
    }

    private List<QueryOutputVector> buildOutputVectors(List<VectorSearchEngine.VectorSearchResult> searchResults,
                                                      Boolean returnDistance, Boolean returnMetadata) {
        List<QueryOutputVector> outputVectors = new ArrayList<>();
        
        for (VectorSearchEngine.VectorSearchResult result : searchResults) {
            QueryOutputVector outputVector = createOutputVector(result, returnDistance, returnMetadata);
            outputVectors.add(outputVector);
        }
        
        return outputVectors;
    }

    private QueryOutputVector createOutputVector(VectorSearchEngine.VectorSearchResult result,
                                                Boolean returnDistance, Boolean returnMetadata) {
        VectorObjectMetadata vectorMetadata = result.vectorMetadata();
        
        QueryOutputVector.QueryOutputVectorBuilder builder = QueryOutputVector.builder()
            .key(vectorMetadata.getVectorId());

        if (shouldReturnDistance(returnDistance)) {
            builder.distance(result.distance());
        }

        if (shouldReturnMetadata(returnMetadata, vectorMetadata)) {
            builder.metadata(vectorMetadata.getMetadata());
        }

        return builder.build();
    }

    private boolean shouldReturnDistance(Boolean returnDistance) {
        return Boolean.TRUE.equals(returnDistance);
    }

    private boolean shouldReturnMetadata(Boolean returnMetadata, VectorObjectMetadata vectorMetadata) {
        return Boolean.TRUE.equals(returnMetadata) && vectorMetadata.getMetadata() != null;
    }

    private QueryVectorsResponse buildResponse(List<QueryOutputVector> outputVectors, DistanceMetric distanceMetric) {
        return QueryVectorsResponse.builder()
            .vectors(outputVectors)
            .distanceMetric(distanceMetric)
            .build();
    }
}
