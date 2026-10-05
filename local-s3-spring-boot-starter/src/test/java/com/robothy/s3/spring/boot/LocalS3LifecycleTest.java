package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LocalS3LifecycleTest {

  @Test
  void namesTheClientsOfTheSingletonsInTheSummaryAndALaterClientOnALineOfItsOwn(CapturedOutput output) {
    LocalS3Lifecycle lifecycle = new LocalS3Lifecycle(LocalS3.builder().port(0).build());
    try {
      lifecycle.endpointFor("S3Client");
      lifecycle.endpointFor("S3Presigner");
      assertTrue(lifecycle.isRunning(), "A client starts the service.");
      assertFalse(output.getOut().contains("Embedded LocalS3: "), "Logged once the singletons are created.");

      lifecycle.afterSingletonsInstantiated();
      lifecycle.start();
      String out = output.getOut();
      assertEquals(1, out.split("Embedded LocalS3: ", -1).length - 1, out);
      assertTrue(out.contains("; S3Client, S3Presigner point at it."), out);

      lifecycle.endpointFor("S3TablesClient");
      assertTrue(output.getOut().contains("The S3TablesClient bean points at the embedded LocalS3 at "
          + lifecycle.endpoint()), output.getOut());
    } finally {
      lifecycle.stop();
    }
  }

  @Test
  void sumsUpAServiceWithoutClientsWhenTheContextStartsIt(CapturedOutput output) {
    LocalS3Lifecycle lifecycle = new LocalS3Lifecycle(LocalS3.builder().port(0).build());
    try {
      lifecycle.afterSingletonsInstantiated();
      assertFalse(output.getOut().contains("Embedded LocalS3: "), "Nothing runs yet.");
      lifecycle.start();
      String out = output.getOut();
      assertEquals(1, out.split("Embedded LocalS3: ", -1).length - 1, out);
      assertFalse(out.contains("point at it"), out);
    } finally {
      lifecycle.stop();
    }
  }

}
