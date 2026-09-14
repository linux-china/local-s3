package com.robothy.s3.rest;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Objects;
import javax.xml.stream.XMLInputFactory;

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
   * @param config the service being started, which carries the options its policies are built from.
   * @param s3Manager the manager of the Amazon S3 data of the service.
   * @param vectorsManager the manager of the S3 Vectors data of the service.
   * @return the assembled factory.
   */
  static ServiceFactory create(LocalS3 config, LocalS3Manager s3Manager, LocalS3VectorsManager vectorsManager) {
    return create(config, s3Manager, vectorsManager, null);
  }

  /**
   * Assemble the services of a service that is starting. The events of the service aren't among them: the manager
   * delivers them to its change listeners, however its services are called.
   *
   * @param config the service being started, which carries the options its policies are built from.
   * @param s3Manager the manager of the Amazon S3 data of the service.
   * @param vectorsManager the manager of the S3 Vectors data of the service.
   * @param admin the administration of the service, which the {@code /_admin} endpoints answer through;
   *     {@code null} for none, which leaves the endpoints out.
   * @return the assembled factory.
   */
  static ServiceFactory create(LocalS3 config, LocalS3Manager s3Manager, LocalS3VectorsManager vectorsManager,
                               LocalS3Admin admin) {
    ServiceFactory serviceFactory = new DefaultServiceFactory();

    BucketService bucketService = s3Manager.bucketService();
    ObjectService objectService = s3Manager.objectService();
    serviceFactory.register(BucketService.class, () -> bucketService);
    serviceFactory.register(ObjectService.class, () -> objectService);

    BucketNameValidator bucketNameValidator = new BucketNameValidator(config.isStrictBucketNames());
    serviceFactory.register(BucketNameValidator.class, () -> bucketNameValidator);
    MultipartUploadPolicy multipartUploadPolicy =
        MultipartUploadPolicy.of(config.isStrictPartSizes(), config.isCompositeMultipartEtags());
    serviceFactory.register(MultipartUploadPolicy.class, () -> multipartUploadPolicy);
    VirtualHostParser virtualHostParser = new VirtualHostParser(config.getVirtualHostDomains());
    serviceFactory.register(VirtualHostParser.class, () -> virtualHostParser);

    XmlMapper xmlMapper = xmlMapper();
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
   * The mapper of the XML documents of the Amazon S3 API. It reads no DTD and no external entity, so that
   * a request body can't make the service read a file or open a connection of its own.
   *
   * @return a new mapper.
   */
  private static XmlMapper xmlMapper() {
    XMLInputFactory input = new WstxInputFactory();
    input.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
    input.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE); // Disable DTDs
    input.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE); // Disable external entities

    XmlMapper xmlMapper = new XmlMapper(new XmlFactory(input, new WstxOutputFactory()));
    xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    xmlMapper.registerModule(new Jdk8Module());
    xmlMapper.registerModule(new JavaTimeModule());
    return xmlMapper;
  }

  /**
   * The mapper of the JSON documents of the S3 Vectors API.
   *
   * @return a new mapper.
   */
  private static ObjectMapper objectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    objectMapper.registerModule(new Jdk8Module());
    objectMapper.registerModule(new JavaTimeModule());
    return objectMapper;
  }

}
