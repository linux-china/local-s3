package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultipartFormDataTest {

  private static final String CONTENT_TYPE = "multipart/form-data; boundary=XyZ";

  @Test
  void readsTheFieldsAndTheFileInPlace() {
    ByteBuf body = body("preamble to ignore\r\n"
        + "--XyZ\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\nuploads/${filename}\r\n"
        + "--XyZ  \r\ncontent-disposition: form-data; name=\"X-Amz-Meta-Note\"\r\n\r\nline one\r\nline two\r\n"
        + "--XyZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"C:\\\\photos\\\\a;b.png\"\r\n"
        + "Content-Type: image/png\r\n\r\n--XyZ is not a delimiter here\r\n"
        + "--XyZ\r\nContent-Disposition: form-data; name=\"after\"\r\n\r\nignored\r\n"
        + "--XyZ--\r\n");

    MultipartFormData form = MultipartFormData.parse(CONTENT_TYPE, body);

    assertEquals("uploads/${filename}", form.field("KEY").orElse(null));
    assertEquals("line one\r\nline two", form.field("x-amz-meta-note").orElse(null));
    assertTrue(form.field("after").isEmpty(), "The fields after the file are ignored.");
    List<String> names = new ArrayList<>();
    form.fields().forEach(field -> names.add(field.name()));
    assertEquals(List.of("key", "X-Amz-Meta-Note"), names);

    assertEquals("a;b.png", form.file().filename());
    assertEquals("image/png", form.file().contentType());
    assertEquals("--XyZ is not a delimiter here", form.file().content().toString(StandardCharsets.UTF_8));
    assertSame(body, form.file().content().unwrap(), "The file is a slice of the body, not a copy.");
  }

  @Test
  void acceptsAQuotedBoundary() {
    MultipartFormData form = MultipartFormData.parse("multipart/form-data; charset=utf-8; boundary=\"a b\"",
        body("--a b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n\r\n\r\n--a b--"));
    assertEquals(0, form.file().content().readableBytes());
  }

  @Test
  void rejectsWhatIsNotAForm() {
    assertError(S3ErrorCode.RequestIsNotMultiPartContent, () -> MultipartFormData.parse(null, body("")));
    assertError(S3ErrorCode.RequestIsNotMultiPartContent,
        () -> MultipartFormData.parse("application/x-www-form-urlencoded", body("key=a")));
    assertError(S3ErrorCode.MalformedPOSTRequest, () -> MultipartFormData.parse("multipart/form-data", body("")));
    assertError(S3ErrorCode.MalformedPOSTRequest, () -> MultipartFormData.parse(CONTENT_TYPE, body("no delimiter")));
    assertError(S3ErrorCode.MalformedPOSTRequest, () -> MultipartFormData.parse(CONTENT_TYPE,
        body("--XyZ\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\nunterminated")));
    assertError(S3ErrorCode.MalformedPOSTRequest, () -> MultipartFormData.parse(CONTENT_TYPE,
        body("--XyZ\r\nContent-Disposition: attachment; name=\"key\"\r\n\r\na\r\n--XyZ--")));
    assertError(S3ErrorCode.MalformedPOSTRequest, () -> MultipartFormData.parse(CONTENT_TYPE,
        body("--XyZ\r\n\r\na\r\n--XyZ--")));
  }

  @Test
  void rejectsAFormWithoutAFile() {
    assertError(S3ErrorCode.IncorrectNumberOfFilesInPostRequest, () -> MultipartFormData.parse(CONTENT_TYPE,
        body("--XyZ\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\na\r\n--XyZ--\r\n")));
  }

  @Test
  void rejectsARepeatedField() {
    assertError(S3ErrorCode.InvalidArgument, () -> MultipartFormData.parse(CONTENT_TYPE, body(
        "--XyZ\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\na\r\n"
            + "--XyZ\r\nContent-Disposition: form-data; name=\"Key\"\r\n\r\nb\r\n"
            + "--XyZ\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nc\r\n--XyZ--")));
  }

  @Test
  void limitsTheFieldsBeforeTheFile() {
    String large = "v".repeat(MultipartFormData.MAX_FIELDS_BYTES);
    assertError(S3ErrorCode.MaxPostPreDataLengthExceededError, () -> MultipartFormData.parse(CONTENT_TYPE, body(
        "--XyZ\r\nContent-Disposition: form-data; name=\"x-amz-meta-large\"\r\n\r\n" + large + "\r\n"
            + "--XyZ\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nc\r\n--XyZ--")));
  }

  private static ByteBuf body(String value) {
    return Unpooled.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertError(S3ErrorCode expected, Runnable parse) {
    LocalS3RequestException thrown = assertThrows(LocalS3RequestException.class, parse::run);
    assertEquals(expected, thrown.getS3ErrorCode());
  }

}
