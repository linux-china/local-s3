package com.robothy.s3.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when the starter defines the clients that point at the embedded service:
 *
 * <ul>
 *   <li>not with {@code local-s3.clients.enabled=false};</li>
 *   <li>with {@code local-s3.clients.enabled=true}, whatever else is configured;</li>
 *   <li>otherwise, unless the application is configured with an S3 endpoint that isn't the embedded service, see
 *   {@linkplain ExternalS3Endpoint}: its clients, e.g. those of Spring Cloud AWS, reach that endpoint instead.</li>
 * </ul>
 */
final class LocalS3ClientsCondition extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    BindResult<Boolean> enabled = Binder.get(context.getEnvironment())
        .bind(ExternalS3Endpoint.CLIENTS_ENABLED, Bindable.of(Boolean.class));
    if (enabled.isBound()) {
      return enabled.get()
          ? ConditionOutcome.match(ExternalS3Endpoint.CLIENTS_ENABLED + " is true")
          : ConditionOutcome.noMatch(ExternalS3Endpoint.CLIENTS_ENABLED + " is false");
    }
    return ExternalS3Endpoint.find(context.getEnvironment())
        .map(external -> ConditionOutcome.noMatch(external.property() + " is " + external.endpoint()
            + ", which isn't the embedded LocalS3"))
        .orElseGet(() -> ConditionOutcome.match("no S3 endpoint other than the embedded LocalS3 is configured"));
  }

}
