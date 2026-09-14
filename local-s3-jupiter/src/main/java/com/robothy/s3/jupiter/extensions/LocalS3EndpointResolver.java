package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;

public class LocalS3EndpointResolver extends AbstractLocalS3ParameterResolver {

    @Override
    protected String className() {
        return LocalS3Endpoint.class.getName();
    }

    @Override
    protected LocalS3Endpoint resolve(int port, LocalS3 s3Config) {
        LocalS3Endpoint localS3Endpoint = new LocalS3Endpoint(port);
        if (s3Config.accessKey() != null && !s3Config.accessKey().isEmpty()) {
            localS3Endpoint.setAccessKey(s3Config.accessKey());
        }
        if (s3Config.secretKey() != null && !s3Config.secretKey().isEmpty()) {
            localS3Endpoint.setSecretKey(s3Config.secretKey());
        }
        return localS3Endpoint;
    }

}
