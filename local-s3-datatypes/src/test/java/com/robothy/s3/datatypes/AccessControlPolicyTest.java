package com.robothy.s3.datatypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.stream.XMLInputFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

class AccessControlPolicyTest {

  @Test
  void serialization() throws JacksonException {

    AccessControlPolicy accessControlPolicy = new AccessControlPolicy();

    Owner owner = new Owner();
    owner.setId("123");
    owner.setDisplayName("Robothy");
    accessControlPolicy.setOwner(owner);

    Grant grant1 = new Grant();
    Grantee grantee1 = new Grantee();
    grantee1.setEmailAddress("abc@123.com");
    grantee1.setId("666");
    grantee1.setDisplayName("ABC");
    grant1.setGrantee(grantee1);
    grant1.setPermission("READ");

    Grant grant2 = new Grant();
    Grantee grantee2 = new Grantee();
    grantee2.setUri("http://localhost/Hello");
    grantee2.setType("CanonicalUser ");
    grant2.setGrantee(grantee2);
    grant2.setPermission("WRITE");

    accessControlPolicy.setGrants(List.of(grant1, grant2));

    XMLInputFactory input = new WstxInputFactory();
    input.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
    // Configured like the mapper of the service. With the defaults of Jackson 3, AUTO_DETECT_XSI_TYPE declares the xsi
    // namespace of xsi:type a second time, next to the xmlns:xsi attribute of Grantee.
    XmlMapper xmlMapper = XmlMapper.builder(XmlFactory.builderWithJackson2Defaults()
            .xmlInputFactory(input)
            .xmlOutputFactory(new WstxOutputFactory())
            .build())
        .configureForJackson2()
        .build();


    String xml = xmlMapper.writerWithDefaultPrettyPrinter()
        .writeValueAsString(accessControlPolicy);

    assertEquals(accessControlPolicy, xmlMapper.readValue(xml, AccessControlPolicy.class));
  }

  @Test
  void deserialization() throws JacksonException {
    String xml = "<AccessControlPolicy xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n" +
                 "	<Owner>\n" +
                 "		<ID>001</ID>\n" +
                 "		<DisplayName>LocalS3</DisplayName>\n" +
                 "	</Owner>\n" +
                 "\n" +
                 "	<AccessControlList>\n" +
                 "		<Grant>\n" +
                 "			<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">\n" +
                 "				<ID>123</ID>\n" +
                 "			</Grantee>\n" +
                 "			<Permission>FULL_CONTROL</Permission>\n" +
                 "		</Grant>\n" +
                 "		<Grant>\n" +
                 "			<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">\n" +
                 "				<ID>001</ID>\n" +
                 "			</Grantee>\n" +
                 "			<Permission>FULL_CONTROL</Permission>\n" +
                 "		</Grant>\n" +
                 "		<Grant>\n" +
                 "			<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">\n" +
                 "				<URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>\n" +
                 "			</Grantee>\n" +
                 "			<Permission>READ</Permission>\n" +
                 "		</Grant>\n" +
                 "	</AccessControlList>\n" +
                 "</AccessControlPolicy>\n";

    XmlMapper xmlMapper = new XmlMapper();
    AccessControlPolicy acl = xmlMapper.readValue(xml, AccessControlPolicy.class);
    Owner owner = acl.getOwner();
    assertEquals("001", owner.getId());
    assertEquals("LocalS3", owner.getDisplayName());

    List<Grant> grants = acl.getGrants();
    assertEquals(3, grants.size());
    List<Grant> groupTypeGrants =
        grants.stream().filter(grant -> "Group".equals(grant.getGrantee().getType())).collect(Collectors.toList());
    assertEquals(1, groupTypeGrants.size());
    assertEquals("READ", groupTypeGrants.get(0).getPermission());
    assertEquals("http://acs.amazonaws.com/groups/global/AllUsers", groupTypeGrants.get(0).getGrantee().getUri());
  }

}