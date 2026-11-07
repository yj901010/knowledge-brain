package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PublicRecordRouteTest {
    String space="0199e000-0000-7000-8000-000000000002",id="0199e000-0000-7000-8000-000000000003";
    @Test void onlyDeclaredReadAndProposalCommandsAreRouted(){String base="/api/public/spaces/"+space;
        for(String path:new String[]{"/records","/records/"+id,"/records/"+id+"/revisions","/records/"+id+"/verification","/explore","/concepts/"+id+"/relations","/record-proposals","/record-proposals/"+id,"/record-proposals/"+id+"/reviews"})assertThat(GatewayRoute.select("GET",base+path)).isNotNull();
        for(String path:new String[]{"/record-proposals","/record-proposals/"+id+"/review","/record-proposals/"+id+"/merge"})assertThat(GatewayRoute.select("POST",base+path)).isNotNull();
        for(String path:new String[]{"/records","/records/"+id,"/record-proposals/"+id,"/record-proposals/"+id+"/approve","/concepts/"+id+"/relations"})assertThat(GatewayRoute.select("PUT",base+path)).isNull();
        assertThat(GatewayRoute.select("POST",base+"/records")).isNull();assertThat(GatewayRoute.select("DELETE",base+"/records/"+id)).isNull();
    }
}
