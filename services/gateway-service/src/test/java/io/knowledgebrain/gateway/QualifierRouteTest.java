package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class QualifierRouteTest {
 @Test void privateQualifierRoutesAreExactAndMethodBounded() {
  String id="01901010-1010-7101-8101-101010101010",base="/api/spaces/"+id+"/concepts/"+id+"/claims/"+id+"/qualifiers";
  for(String method:new String[]{"GET","POST"})assertThat(GatewayRoute.select(method,base).service()).isEqualTo("knowledge");
  for(String method:new String[]{"GET","PUT","DELETE"})assertThat(GatewayRoute.select(method,base+"/"+id).service()).isEqualTo("knowledge");
  assertThat(GatewayRoute.select("GET",base+"/"+id+"/revisions").service()).isEqualTo("knowledge");
  assertThat(GatewayRoute.select("PATCH",base+"/"+id)).isNull();
  assertThat(GatewayRoute.select("POST",base+"/"+id+"/revisions")).isNull();
  assertThat(GatewayRoute.select("GET",base+"/extra/"+id)).isNull();
  assertThat(GatewayRoute.select("GET",base.replace("/api/spaces/","/api/public/spaces/"))).isNull();
 }
}
