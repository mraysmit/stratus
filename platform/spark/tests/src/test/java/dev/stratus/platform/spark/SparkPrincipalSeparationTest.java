// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves that two catalog principals using the platform at the same time are
 * separated by what each was granted, not by what each was asked to do.
 *
 * <p>This is the test the previous suite could not express. Every command it
 * ran went through a shell inside the master container and used that
 * container's ambient identity, so there was one principal, no way to introduce
 * a second, and nothing that could distinguish authorisation from a broken
 * client.
 *
 * <p>Both halves run against the same cluster at the same moment. A refusal on
 * its own proves very little — a client that cannot reach anything also fails —
 * so the privileged client makes the identical call concurrently, and its
 * success is what makes the other's refusal mean something.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("spark-integration")
final class SparkPrincipalSeparationTest {

    @RegisterExtension
    private static final SparkSuiteContext SPARK = new SparkSuiteContext();

    private static final String SUFFIX =
            UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String UNGRANTED_PRINCIPAL = "svc-probe-" + SUFFIX;

    private static PolarisPrincipals principals;
    private static StratusSparkClient granted;
    private static StratusSparkClient ungranted;
    private static String ungrantedSecret;

    @BeforeAll
    static void provisionTwoPrincipals() {
        LiveSparkCluster.require();
        principals = PolarisPrincipals.connect();

        // A secret this test chose, so nothing has to be read back out of the
        // catalog: Polaris will not hand a secret back once it is set.
        ungrantedSecret = UUID.randomUUID().toString().replace("-", "");
        String credential =
                principals.createWithoutCatalogAccess(UNGRANTED_PRINCIPAL, ungrantedSecret);

        granted = SPARK.client(
                SparkClientConfig.serviceIdentity("stratus-granted-client", 17077, 17078)
                        .withApplicationCores(2));
        ungranted = granted.asAnotherPrincipal(
                granted.config().asPrincipal("stratus-ungranted-client", credential, 17077, 17078));
    }

    @AfterAll
    static void removeTheProbePrincipal() {
        if (ungranted != null) {
            ungranted.close();
        }
        if (granted != null) {
            granted.close();
        }
        if (principals != null) {
            // Asserted by consequence: the principal must no longer be able to
            // obtain a token. A removal whose result is discarded leaves a
            // usable identity behind in the catalog.
            principals.remove(UNGRANTED_PRINCIPAL);
            assertThrows(RuntimeException.class,
                    () -> principals.token(UNGRANTED_PRINCIPAL, "irrelevant"),
                    "the removed principal must no longer authenticate");
        }
    }

    @Test
    void anUngrantedPrincipalAuthenticatesAndIsStillRefusedTheData() {
        // Authentication first. Without this the refusal below would be
        // satisfied by a principal that simply does not exist, which proves
        // nothing about authorisation.
        assertNotNull(principals.token(UNGRANTED_PRINCIPAL, ungrantedSecret),
                "the probe principal must be able to prove who it is");

        // The catalog refuses outright rather than answering with an empty
        // list, which is the stronger behaviour: an empty answer is
        // indistinguishable from an empty catalog, while a refusal names the
        // principal, the roles it presented, and the operation denied.
        var refused = assertThrows(Exception.class,
                () -> ungranted.sql("SHOW NAMESPACES IN stratus"),
                "a principal granted nothing in the catalog must be refused");

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("not authorized for op LIST_NAMESPACES"),
                "the refusal must name the operation denied: " + message);
        assertTrue(message.contains(UNGRANTED_PRINCIPAL),
                "and the principal denied it: " + message);
    }

    @Test
    void twoPrincipalsUseTheClusterAtTheSameTimeAndSeeDifferentThings() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            Callable<List<String>> grantedCall = () -> granted.sql("SHOW NAMESPACES IN stratus")
                    .stream().map(row -> row.get(0).toString()).toList();
            Callable<String> ungrantedCall = () -> {
                try {
                    ungranted.sql("SHOW NAMESPACES IN stratus");
                    return "ALLOWED";
                } catch (Exception refused) {
                    return String.valueOf(refused.getMessage());
                }
            };

            Future<List<String>> first = clients.submit(grantedCall);
            Future<String> second = clients.submit(ungrantedCall);

            List<String> grantedSees = first.get(4, TimeUnit.MINUTES);
            String ungrantedOutcome = second.get(4, TimeUnit.MINUTES);

            // The positive control, taken at the same moment as the refusal:
            // without it, a refusal is equally well explained by a platform
            // that was answering nobody.
            assertTrue(grantedSees.contains("bronze"),
                    "the granted principal must see the governed zones: " + grantedSees);
            assertTrue(grantedSees.contains("silver"),
                    "including silver: " + grantedSees);
            assertTrue(ungrantedOutcome.contains("not authorized"),
                    "the ungranted principal must be refused at the same moment: "
                            + ungrantedOutcome);
        } finally {
            clients.shutdownNow();
        }
    }

    @Test
    void bothClientsRunOnTheClusterRatherThanInThisJvm() {
        // Both sessions share one driver, so both carry the cluster's own
        // application id. A session that had fallen back to local mode would
        // answer every query above just as happily.
        assertTrue(granted.applicationId().startsWith("app-"),
                "the granted client must run on the cluster: " + granted.applicationId());
        assertTrue(ungranted.applicationId().startsWith("app-"),
                "the ungranted client must run on the cluster: " + ungranted.applicationId());
    }
}
