package org.dashj.platform.dashpay

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.dpp.ProtocolVersion
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DashPayWalletExtensionTest : PlatformNetwork() {
    @Test
    fun roundTripTest() {
        val extension = DashPayWalletExtension(platform, authenticationGroupExtension) // (platform, wallet)
        extension.blockchainIdentity = BlockchainIdentity(platform, 0, wallet, authenticationGroupExtension)

        val expectedIdentity = Identity(
            Identifier.Companion.from(Sha256Hash.ZERO_HASH),
            mutableListOf(
                IdentityPublicKey(
                    0,
                    KeyType.ECDSA_SECP256K1,
                    Purpose.AUTHENTICATION,
                    SecurityLevel.MASTER,
                    ECKey().pubKey,
                    true
                )
            ),
            50000L,
            1,
            ProtocolVersion.latestVersion
        )

        extension.blockchainIdentity!!.identity = expectedIdentity

        val bytes = extension.serializeWalletExtension()

        assertEquals(94, bytes.size)

        val extensionTwo = DashPayWalletExtension(platform, authenticationGroupExtension)
        extensionTwo.blockchainIdentity = BlockchainIdentity(platform, 0, wallet, authenticationGroupExtension)
        extensionTwo.deserializeWalletExtension(wallet, bytes)

        assertEquals(expectedIdentity, extensionTwo.blockchainIdentity!!.identity)
    }

    @Test
    fun roundTripWithoutIdentityTest() {
        val extension = DashPayWalletExtension(platform, authenticationGroupExtension)
        extension.blockchainIdentity = BlockchainIdentity(platform, 0, wallet, authenticationGroupExtension)
        val bytes = extension.serializeWalletExtension()

        assertEquals(0, bytes.size)

        val extensionTwo = DashPayWalletExtension(platform, authenticationGroupExtension)
        extensionTwo.blockchainIdentity = BlockchainIdentity(platform, 0, wallet, authenticationGroupExtension)
        extensionTwo.deserializeWalletExtension(wallet, bytes)
    }
}
