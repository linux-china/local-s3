package com.robothy.s3.rest.assertions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.S3ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestAssertionsTest {

  private static HttpRequest withMaxKeys(String maxKeys) {
    HttpRequest request = HttpRequest.builder().build();
    if (maxKeys != null) {
      request.getParams().put("max-keys", List.of(maxKeys));
    }
    return request;
  }

  @Test
  void maxKeysDefaultsToTheMostKeysAListingReturns() {
    assertEquals(RequestAssertions.DEFAULT_MAX_KEYS, RequestAssertions.assertMaxKeysIsValid(withMaxKeys(null)));
  }

  @ValueSource(strings = {"0", "1", "999", "1000", " 20 "})
  @ParameterizedTest
  void maxKeysKeepsTheRequestedNumberOfKeys(String maxKeys) {
    assertEquals(Integer.parseInt(maxKeys.trim()), RequestAssertions.assertMaxKeysIsValid(withMaxKeys(maxKeys)));
  }

  /**
   * A listing of Amazon S3 never returns more than 1000 keys, but it doesn't reject a request that asks for more.
   */
  @ValueSource(strings = {"1001", "5000", "2147483647"})
  @ParameterizedTest
  void maxKeysAboveTheLimitIsCappedRatherThanRejected(String maxKeys) {
    assertEquals(RequestAssertions.DEFAULT_MAX_KEYS, RequestAssertions.assertMaxKeysIsValid(withMaxKeys(maxKeys)));
  }

  /**
   * A negative value would let the listing return every key, since the number of collected keys never reaches it.
   */
  @ValueSource(strings = {"-1", "-1000", "abc", "", "1.5", "1e3", "9999999999"})
  @ParameterizedTest
  void maxKeysThatIsNotANumberOrIsNegativeIsRejected(String maxKeys) {
    LocalS3InvalidArgumentException thrown = assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestAssertions.assertMaxKeysIsValid(withMaxKeys(maxKeys)));
    assertEquals(S3ErrorCode.InvalidArgument, thrown.getS3ErrorCode());
    assertEquals("max-keys", thrown.getArgumentName());
    assertEquals(maxKeys, thrown.getArgumentValue());
  }

}
