package com.robothy.s3.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.DefaultServiceFactory;
import com.robothy.s3.rest.service.MultipartUploadPolicy;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.VirtualHostParser;
import com.robothy.s3.rest.utils.XmlUtils;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Assembles the {@linkplain ServiceFactory} that the request handlers of a running service resolve their
 * collaborators through: the services of the data of the service, the policies that the options of
 * {@linkplain LocalS3} configure, and the mappers that read and write the documents of the Amazon S3 and
 * the S3 Vectors APIs.
 *
 * <p>Every entry is registered as an instance that was built once, so that the handlers of a running
 * service share it rather than building one per request.
 */
final class LocalS3Services {

  private LocalS3Services() {
  }

  /**
   * Assemble the services of a service that is starting.
   *
   * @param config the configuration of the service, which its policies are built from.
   * @param s3Manager the manager of the Amazon S3 data of the service.
   * @param vectorsManager the manager of the S3 Vectors data of the service.
   * @return the assembled factory.
   */
  static ServiceFactory create(LocalS3Config config, LocalS3Manager s3Manager, LocalS3VectorsManager vectorsManager) {
    return create(config, s3Manager, vectorsManager, null);
  }

  /**
   * Assemble the services of a service that is starting. The events of the service aren't among them: the manager
   * delivers them to its change listeners, however its services are called.
   *
   * @param config the configuration of the service, which its policies are built from.
   * @param s3Manager the manager of the Amazon S3 data of the service.
   * @param vectorsManager the manager of the S3 Vectors data of the service.
   * @param admin the administration of the service, which the {@code /_admin} endpoints answer through;
   *     {@code null} for none, which leaves the endpoints out.
   * @return the assembled factory.
   */
  static ServiceFactory create(LocalS3Config config, LocalS3Manager s3Manager, LocalS3VectorsManager vectorsManager,
                               LocalS3Admin admin) {
    ServiceFactory serviceFactory = new DefaultServiceFactory();

    BucketService bucketService = s3Manager.bucketService();
    ObjectService objectService = s3Manager.objectService();
    serviceFactory.register(BucketService.class, () -> bucketService);
    serviceFactory.register(ObjectService.class, () -> objectService);

    BucketNameValidator bucketNameValidator = new BucketNameValidator();
    serviceFactory.register(BucketNameValidator.class, () -> bucketNameValidator);
    MultipartUploadPolicy multipartUploadPolicy = MultipartUploadPolicy.of(config.compositeMultipartEtags());
    serviceFactory.register(MultipartUploadPolicy.class, () -> multipartUploadPolicy);
    // The configuration itself, for the handlers that answer URLs of the service, whose scheme depends on its TLS.
    serviceFactory.register(LocalS3Config.class, () -> config);
    VirtualHostParser virtualHostParser = new VirtualHostParser(config.virtualHostDomains());
    serviceFactory.register(VirtualHostParser.class, () -> virtualHostParser);

    XmlMapper xmlMapper = XmlUtils.createXmlMapper();
    serviceFactory.register(XmlMapper.class, () -> xmlMapper);
    ObjectMapper objectMapper = objectMapper();
    serviceFactory.register(ObjectMapper.class, () -> objectMapper);

    S3VectorsService s3VectorsService = vectorsManager.s3VectorsService();
    serviceFactory.register(S3VectorsService.class, () -> s3VectorsService);

    if (Objects.nonNull(admin)) {
      serviceFactory.register(LocalS3Admin.class, () -> admin);
    }
    return serviceFactory;
  }

  /**
   * The mapper of the JSON documents of the S3 Vectors API, configured like Jackson 2 configured a mapper.
   *
   * @return a new mapper.
   */
  private static ObjectMapper objectMapper() {
    return JsonMapper.builderWithJackson2Defaults()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
        .build();
  }

}
