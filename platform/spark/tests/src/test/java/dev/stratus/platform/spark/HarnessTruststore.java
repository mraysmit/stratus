// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A truststore carrying the harness certificate authorities, built in this JVM.
 *
 * <p>The lab CAs are privately issued, so nothing trusts them by default. A
 * client that talks to Polaris or the object store over TLS must be told about
 * them, and that is a client's own problem — which is why this is Java and not
 * a {@code keytool} invocation in a wrapper script.
 *
 * <p>It starts from the JDK's own {@code cacerts} rather than an empty store.
 * The trust store applies to the whole JVM, and a store holding only the lab
 * CAs would break every public endpoint the process might also reach.
 *
 * <p><strong>The driver and the executors need different paths to the same
 * thing.</strong> This file is on the workstation and configures the driver.
 * The executors run in containers where the compose file already mounts an
 * equivalent store, so their path is a container path. Setting only one of them
 * produces the failure the Spark configuration template warns about: the
 * catalog resolves, and then every write fails with a PKIX error from the
 * storage client.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
final class HarnessTruststore {

    /** The path the containers mount their own copy at; see the class comment. */
    static final String CONTAINER_PATH = "/opt/stratus/certs/stratus-truststore.jks";

    private static final String PASSWORD = "changeit";

    private static Path built;

    private HarnessTruststore() {
    }

    /**
     * Builds the store once per JVM and points this process at it.
     *
     * <p>Applied through system properties before any TLS client is created.
     * A client built earlier keeps the default trust manager it was born with,
     * and no later change reaches it.
     */
    static synchronized Path installed() {
        if (built != null) {
            return built;
        }
        Path target = HarnessConnection.repositoryRoot()
                .resolve("platform/spark/tests/target/stratus-client-truststore.jks");
        var authorities = new LinkedHashMap<String, Path>();
        authorities.put("stratus-ceph-lab-ca", HarnessConnection.cephCertificateAuthority());
        authorities.put("stratus-polaris-lab-ca", HarnessConnection.polarisCertificateAuthority());

        KeyStore store = build(target, authorities);

        // Both, and in this order, because they reach different things.
        //
        // The properties are read when the JVM first builds its default
        // SSLContext, and are all a library that constructs its own context
        // will see. But the default context is built once and cached, and
        // anything that has already made an HTTPS connection — or merely
        // constructed an HttpClient, which resolves the default eagerly — has
        // fixed it. Replacing the default outright is what makes this work
        // regardless of what ran first, and PKIX failures against a truststore
        // that plainly contains the right CA are what happens without it.
        System.setProperty("javax.net.ssl.trustStore", target.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", PASSWORD);
        replaceDefaultContext(store);

        built = target;
        return target;
    }

    private static void replaceDefaultContext(KeyStore store) {
        try {
            var trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            SSLContext.setDefault(context);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Failed to trust the harness certificate authorities", exception);
        }
    }

    private static KeyStore build(Path target, Map<String, Path> authorities) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            Path defaults = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
            try (InputStream in = Files.newInputStream(defaults)) {
                store.load(in, PASSWORD.toCharArray());
            }

            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            for (Map.Entry<String, Path> authority : authorities.entrySet()) {
                if (!Files.isReadable(authority.getValue())) {
                    throw new IllegalStateException("Missing harness certificate authority "
                            + authority.getValue() + "; start the harness that issues it");
                }
                try (InputStream in = Files.newInputStream(authority.getValue())) {
                    store.setCertificateEntry(authority.getKey(),
                            (X509Certificate) certificates.generateCertificate(in));
                }
            }

            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                store.store(out, PASSWORD.toCharArray());
            }
            return store;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to build " + target, exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to build " + target, exception);
        }
    }
}
