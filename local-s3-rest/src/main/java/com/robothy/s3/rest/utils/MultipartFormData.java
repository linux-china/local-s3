package com.robothy.s3.rest.utils;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The fields and the file of a {@code multipart/form-data} body, as a browser posts an HTML form to upload a file to
 * a bucket, see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPOST.html">POST Object</a>.
 *
 * <p>The body is read in place: the content of the file is a slice of the body, not a copy, so that a large upload,
 * whose body is a memory-mapped file, is neither copied nor loaded into the heap. The slice is only valid while the
 * body is.
 *
 * <p>Like Amazon S3, the parts that follow the {@code file} field are ignored, and the fields that precede it are
 * limited in size, see {@linkplain #MAX_FIELDS_BYTES}.
 */
public final class MultipartFormData {

  /**
   * The name of the field whose part is the content of the object.
   */
  public static final String FILE_FIELD = "file";

  /**
   * The max size of the fields that precede the file, which Amazon S3 limits to 20 KB.
   */
  public static final int MAX_FIELDS_BYTES = 20 * 1024;

  private static final byte[] CRLF = {'\r', '\n'};

  private static final byte[] HEADERS_END = {'\r', '\n', '\r', '\n'};

  /**
   * The fields by their names in lower case, since Amazon S3 compares field names ignoring case, in the order of
   * the form.
   */
  private final Map<String, Field> fields;

  private final FilePart file;

  private MultipartFormData(Map<String, Field> fields, FilePart file) {
    this.fields = Collections.unmodifiableMap(fields);
    this.file = file;
  }

  /**
   * A field of the form.
   *
   * @param name the name of the field, as the form spells it.
   * @param value the value of the field.
   */
  public record Field(String name, String value) {
  }

  /**
   * The file of the form.
   *
   * @param filename the name of the file that was selected, without a directory; empty if the form names none.
   * @param contentType the {@code Content-Type} of the part; {@code null} if it has none.
   * @param content the content of the file, a slice of the body.
   */
  public record FilePart(String filename, String contentType, ByteBuf content) {
  }

  /**
   * Parse a {@code multipart/form-data} body.
   *
   * @param contentType the {@code Content-Type} header of the request.
   * @param body the body of the request; {@code null} for an empty one.
   * @return the fields and the file of the form.
   * @throws LocalS3RequestException {@code RequestIsNotMultiPartContent} if the request isn't
   *     {@code multipart/form-data}, {@code MalformedPOSTRequest} if the body isn't well-formed,
   *     {@code MaxPostPreDataLengthExceededError} if the fields preceding the file are too large, and
   *     {@code IncorrectNumberOfFilesInPostRequest} if the form has no file.
   */
  public static MultipartFormData parse(String contentType, ByteBuf body) {
    String boundary = boundary(contentType);
    ByteBuf content = body == null ? Unpooled.EMPTY_BUFFER : body;
    ByteBuf delimiter = Unpooled.wrappedBuffer(("--" + boundary).getBytes(StandardCharsets.ISO_8859_1));

    int start = content.readerIndex();
    int end = content.writerIndex();
    // The body starts with the first delimiter, possibly after a preamble, which is ignored.
    int delimiterIndex = indexOf(content, delimiter, start, end);
    if (delimiterIndex < 0) {
      throw malformed();
    }

    Map<String, Field> fields = new LinkedHashMap<>();
    int position = delimiterIndex + delimiter.readableBytes();
    while (true) {
      if (startsWith(content, position, end, (byte) '-', (byte) '-')) {
        // The close delimiter, without a file.
        throw new LocalS3RequestException(S3ErrorCode.IncorrectNumberOfFilesInPostRequest);
      }
      position = skipLinearWhitespace(content, position, end);
      if (!startsWith(content, position, end, CRLF[0], CRLF[1])) {
        throw malformed();
      }
      int headersStart = position + CRLF.length;
      int headersEnd = indexOf(content, Unpooled.wrappedBuffer(HEADERS_END), position, end);
      if (headersEnd < headersStart) {
        throw malformed();
      }
      PartHeaders headers = PartHeaders.parse(content.toString(headersStart, headersEnd - headersStart,
          StandardCharsets.UTF_8));
      int partStart = headersEnd + HEADERS_END.length;
      // A part ends at the CRLF that precedes the next delimiter.
      int nextDelimiter = indexOf(content, Unpooled.wrappedBuffer(CRLF, ("--" + boundary)
          .getBytes(StandardCharsets.ISO_8859_1)), partStart, end);
      if (nextDelimiter < 0) {
        throw malformed();
      }

      if (FILE_FIELD.equalsIgnoreCase(headers.name())) {
        // The parts that follow the file are ignored, like Amazon S3 ignores them.
        return new MultipartFormData(fields, new FilePart(baseName(headers.filename()), headers.contentType(),
            content.slice(partStart, nextDelimiter - partStart)));
      }

      if (nextDelimiter - start > MAX_FIELDS_BYTES) {
        throw new LocalS3RequestException(S3ErrorCode.MaxPostPreDataLengthExceededError);
      }
      String name = headers.name();
      String value = content.toString(partStart, nextDelimiter - partStart, StandardCharsets.UTF_8);
      if (fields.putIfAbsent(name.toLowerCase(Locale.ROOT), new Field(name, value)) != null) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidArgument,
            "The form field " + name + " is specified more than once.");
      }
      position = nextDelimiter + CRLF.length + delimiter.readableBytes();
    }
  }

  /**
   * The value of a field, whose name is compared ignoring case.
   *
   * @param name the name of the field.
   * @return the value; empty if the form has no such field.
   */
  public Optional<String> field(String name) {
    return Optional.ofNullable(fields.get(name.toLowerCase(Locale.ROOT))).map(Field::value);
  }

  /**
   * The fields that precede the file, in the order of the form.
   *
   * @return the fields.
   */
  public Iterable<Field> fields() {
    return fields.values();
  }

  /**
   * The file of the form.
   *
   * @return the file.
   */
  public FilePart file() {
    return file;
  }

  /**
   * The boundary of a {@code multipart/form-data} content type.
   */
  private static String boundary(String contentType) {
    if (contentType == null) {
      throw new LocalS3RequestException(S3ErrorCode.RequestIsNotMultiPartContent);
    }
    String[] parameters = contentType.split(";");
    if (!"multipart/form-data".equalsIgnoreCase(parameters[0].trim())) {
      throw new LocalS3RequestException(S3ErrorCode.RequestIsNotMultiPartContent);
    }
    for (int i = 1; i < parameters.length; i++) {
      String parameter = parameters[i].trim();
      int equals = parameter.indexOf('=');
      if (equals > 0 && "boundary".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
        String boundary = unquote(parameter.substring(equals + 1).trim());
        // RFC 2046: 1 to 70 characters.
        if (boundary.isEmpty() || boundary.length() > 70) {
          throw malformed();
        }
        return boundary;
      }
    }
    throw malformed();
  }

  /**
   * The name of a file without the directory that some browsers send it with.
   */
  private static String baseName(String filename) {
    if (filename == null) {
      return "";
    }
    int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
    return filename.substring(separator + 1);
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    return value;
  }

  private static int indexOf(ByteBuf haystack, ByteBuf needle, int from, int to) {
    if (from >= to) {
      return -1;
    }
    int index = ByteBufUtil.indexOf(needle, haystack.slice(from, to - from));
    return index < 0 ? -1 : from + index;
  }

  private static boolean startsWith(ByteBuf buffer, int index, int end, byte first, byte second) {
    return index + 1 < end && buffer.getByte(index) == first && buffer.getByte(index + 1) == second;
  }

  /**
   * Skip the whitespace that RFC 2046 allows after a delimiter, before its CRLF.
   */
  private static int skipLinearWhitespace(ByteBuf buffer, int index, int end) {
    int position = index;
    while (position < end && (buffer.getByte(position) == ' ' || buffer.getByte(position) == '\t')) {
      position++;
    }
    return position;
  }

  private static LocalS3RequestException malformed() {
    return new LocalS3RequestException(S3ErrorCode.MalformedPOSTRequest);
  }

  /**
   * The headers of a part that matter: the name of its field, the name of its file, and its content type.
   */
  private record PartHeaders(String name, String filename, String contentType) {

    static PartHeaders parse(String headers) {
      String name = null;
      String filename = null;
      String contentType = null;
      for (String line : headers.split("\r\n")) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
          continue;
        }
        String header = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        if ("Content-Disposition".equalsIgnoreCase(header)) {
          String[] parameters = splitParameters(value);
          if (!"form-data".equalsIgnoreCase(parameters[0].trim())) {
            throw malformed();
          }
          for (int i = 1; i < parameters.length; i++) {
            String parameter = parameters[i].trim();
            int equals = parameter.indexOf('=');
            if (equals <= 0) {
              continue;
            }
            String parameterName = parameter.substring(0, equals).trim();
            String parameterValue = unquote(parameter.substring(equals + 1).trim());
            if ("name".equalsIgnoreCase(parameterName)) {
              name = parameterValue;
            } else if ("filename".equalsIgnoreCase(parameterName)) {
              filename = parameterValue;
            }
          }
        } else if ("Content-Type".equalsIgnoreCase(header)) {
          contentType = value;
        }
      }
      if (name == null || name.isEmpty()) {
        throw malformed();
      }
      return new PartHeaders(name, filename, contentType);
    }

    /**
     * Split the parameters of a header value at the semicolons that aren't quoted, since a file name may contain one.
     */
    private static String[] splitParameters(String value) {
      List<String> parameters = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c == '\\' && quoted && i + 1 < value.length()) {
          current.append(c).append(value.charAt(++i));
        } else if (c == '"') {
          quoted = !quoted;
          current.append(c);
        } else if (c == ';' && !quoted) {
          parameters.add(current.toString());
          current.setLength(0);
        } else {
          current.append(c);
        }
      }
      parameters.add(current.toString());
      return parameters.toArray(String[]::new);
    }
  }

}
