package io.knowledgebrain.brainquery;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import io.valkey.*;
import io.valkey.params.SetParams;

/** One scoped ACL identity, TLS with CA and DNS verification, no eager connection. */
final class ValkeyPublicSearchTransport implements PublicSearchCache.Transport {
    private final JedisPool pool;
    ValkeyPublicSearchTransport(String host, int port, Path directory) throws Exception {
        if (host == null || !host.matches("[a-zA-Z0-9.-]{1,253}") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Fixed Valkey host required");
        String user = Files.readString(directory.resolve("username")).strip();
        String password = Files.readString(directory.resolve("password")).strip();
        if (!user.equals("public-search") || !password.matches("[A-Za-z0-9_-]{43,128}"))
            throw new IllegalArgumentException("Scoped cache credentials required");
        var store = KeyStore.getInstance("PKCS12"); store.load(null, null);
        try (var stream = Files.newInputStream(directory.resolve("ca.crt"))) {
            int index = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(stream))
                store.setCertificateEntry("ca-" + index++, cert);
            if (index == 0) throw new IllegalArgumentException("Cache CA required");
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(store);
        var tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        var parameters = new SSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        var config = DefaultJedisClientConfig.builder().user(user).password(password).database(0)
                .connectionTimeoutMillis(100).socketTimeoutMillis(100).ssl(true)
                .sslSocketFactory(tls.getSocketFactory()).sslParameters(parameters).build();
        var bounds = new JedisPoolConfig(); bounds.setMaxTotal(2); bounds.setMaxIdle(2); bounds.setMinIdle(0);
        bounds.setBlockWhenExhausted(false); bounds.setTestWhileIdle(false); bounds.setTestOnBorrow(false);
        pool = new JedisPool(bounds, new HostAndPort(host, port), config);
    }
    private static byte[] key(String key) {
        if (!key.matches("brain:public-search:v1:[0-9a-f]{64}")) throw PublicProjection.invalid();
        return key.getBytes(StandardCharsets.US_ASCII);
    }
    @Override public byte[] read(String key) {
        try (var client = pool.getResource()) { return client.getrange(key(key), 0, PublicSearchCache.MAX_BYTES); }
    }
    @Override public void write(String key, byte[] value, int ttlSeconds) {
        if (value.length > PublicSearchCache.MAX_BYTES || ttlSeconds != PublicSearchCache.TTL_SECONDS)
            throw PublicProjection.invalid();
        try (var client = pool.getResource()) { client.set(key(key), value, SetParams.setParams().ex(ttlSeconds)); }
    }
    @Override public void close() { pool.close(); }
}
