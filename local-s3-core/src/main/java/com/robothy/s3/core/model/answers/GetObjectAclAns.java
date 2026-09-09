package com.robothy.s3.core.model.answers;

import com.robothy.s3.datatypes.AccessControlPolicy;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class GetObjectAclAns {

  private String versionId;

  private AccessControlPolicy acl;

}
