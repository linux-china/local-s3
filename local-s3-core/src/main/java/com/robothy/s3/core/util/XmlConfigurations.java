package com.robothy.s3.core.util;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import java.io.StringReader;
import java.util.Optional;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Checks the configuration documents that LocalS3 stores as they were put rather than reads, e.g. the notification
 * configuration of a bucket.
 */
public final class XmlConfigurations {

  private XmlConfigurations() {
  }

  /**
   * Assert that a configuration is a well-formed XML document whose root element is the expected one. Its contents
   * aren't checked.
   *
   * @param configuration the document.
   * @param rootElement the local name of the root element, e.g. {@code NotificationConfiguration}.
   * @throws LocalS3RequestException with {@linkplain S3ErrorCode#MalformedXML} if the document is blank, isn't
   *     well-formed, has another root element, or declares a document type.
   */
  public static void assertWellFormed(String configuration, String rootElement) {
    if (configuration == null || configuration.isBlank()) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    // A configuration has no document type, and its entities must never be resolved.
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(configuration));
      try {
        boolean rootSeen = false;
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
          }
          if (event == XMLStreamConstants.START_ELEMENT && !rootSeen) {
            if (!rootElement.equals(reader.getLocalName())) {
              throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
            }
            rootSeen = true;
          }
        }
        if (!rootSeen) {
          throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
        }
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
  }

  /**
   * Read the text of a child of the root element of a well-formed configuration, e.g. the {@code Id} of a
   * {@code MetricsConfiguration}.
   *
   * @param configuration the document, which {@linkplain #assertWellFormed} accepted.
   * @param childElement the local name of the child.
   * @return the trimmed text of the first child of that name; empty if the root has none.
   * @throws LocalS3RequestException with {@linkplain S3ErrorCode#MalformedXML} if the document isn't well-formed.
   */
  public static Optional<String> childText(String configuration, String childElement) {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(configuration));
      try {
        int depth = 0;
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            depth++;
            if (depth == 2 && childElement.equals(reader.getLocalName())) {
              return Optional.of(reader.getElementText().trim());
            }
          } else if (event == XMLStreamConstants.END_ELEMENT) {
            depth--;
          }
        }
        return Optional.empty();
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
  }

}
