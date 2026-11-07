package io.knowledgebrain.identity;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.identity.login.enabled",havingValue="true")
class RegisteredLoginConfiguration {
    @Bean OidcLoginService oidcLoginService(DataSource identityDataSource,
        @Value("${brain.identity.login.public-origin}") String publicOrigin,
        @Value("${brain.identity.login.backend-origin}") String backend,
        @Value("${brain.identity.login.trust-file}") String trust,
        @Value("${brain.identity.login.client-secret}") String secret,
        @Value("${brain.identity.login.encryption-key}") String encryption,
        @Value("${brain.identity.browser-origin}") String browser) throws Exception {
        OidcProvider.origin(browser);return new OidcLoginService(identityDataSource,java.time.Clock.systemUTC(),new TokenCipher(encryption),new OidcProvider(publicOrigin,backend,trust,"brain-web",secret,browser+"/api/identity/login/callback"));
    }
}
