package io.knowledgebrain.space;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class PublicKnowledgeSpaces {
    record View(UUID spaceId,String domain,String title,boolean canPropose,boolean canReview) {}
    private final JdbcTemplate jdbc;
    PublicKnowledgeSpaces(DataSource source){jdbc=new JdbcTemplate(source);}
    List<View> list(IdentityPrincipalClient.Principal principal){
        return jdbc.query("SELECT s.space_id,c.domain_key,c.title,m.role FROM public_knowledge_space c JOIN space s ON s.space_id=c.space_id LEFT JOIN space_membership m ON m.space_id=s.space_id AND m.principal_id=? AND m.state='ACTIVE' WHERE s.kind='PUBLIC' AND s.visibility='PUBLIC' AND s.state='ACTIVE' ORDER BY c.domain_key",
            (rs,n)->new View(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),"REGISTERED".equals(principal.kind()),"REGISTERED".equals(principal.kind())&&Set.of("REVIEWER","ADMIN","OWNER").contains(Optional.ofNullable(rs.getString(4)).orElse(""))),principal.principalId());
    }
    void authorize(UUID space,IdentityPrincipalClient.Principal principal,String action){
        var view=list(principal).stream().filter(s->s.spaceId().equals(space)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Public Space unavailable"));
        if(!"PUBLIC_CONCEPT_READ".equals(action) && !view.canPropose())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered account required");
        if(Set.of("CONTRIBUTION_REVIEW","CONTRIBUTION_MERGE","CLAIM_EXPERT_VERIFY","MAPPING_OFFICIAL_REVIEW").contains(action)&&!view.canReview())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Designated reviewer required");
        if(Set.of("CLAIM_EXPERT_VERIFY","MAPPING_OFFICIAL_REVIEW").contains(action)&&jdbc.queryForObject("SELECT count(*) FROM public_knowledge_capability WHERE space_id=? AND principal_id=? AND capability=? AND state='ACTIVE'",Integer.class,space,principal.principalId(),action)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit operator capability required");
    }
}
