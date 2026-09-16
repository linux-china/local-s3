package com.robothy.s3.datatypes.serializer;

import com.robothy.s3.datatypes.Grantee;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

public class GranteeSerializer extends StdSerializer<Grantee> {

  public GranteeSerializer(Class<Grantee> t) {
    super(t);
  }

  @Override
  public void serialize(Grantee value, JsonGenerator gen, SerializationContext provider) {
    gen.writeStartObject();
    gen.writePOJO(value.getDisplayName());
    gen.writePOJO(value.getEmailAddress());
    gen.writePOJO(value.getId());
    gen.writePOJO(value.getUri());
    gen.writeStringProperty("type", value.getType());
    gen.writeEndObject();
  }
}
