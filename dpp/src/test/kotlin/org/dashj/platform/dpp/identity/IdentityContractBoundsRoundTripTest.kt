/**
 * Copyright (c) 2020-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.platform.dpp.identity

import org.dashj.platform.dpp.Factory
import org.dashj.platform.dpp.ProtocolVersion
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WHOLE-[Identity] round-trip coverage for contract-bound public keys.
 *
 * [IdentityPublicKeyTest] covers the same regression one level down, on a single
 * [IdentityPublicKey]. The failure actually reported from production was at the
 * IDENTITY level: `Identity.toBuffer()` on a freshly registered 6-key identity
 * threw `IllegalArgumentException("No converter for ...SingleContractDocumentType...")`
 * because keys 4 (ENCRYPTION) and 5 (DECRYPTION) carry
 * `SingleContractDocumentType(dashpay, "contactRequest")`, and the throw stalled
 * identity creation while persisting the local identity cache.
 *
 * That path has two extra links the per-key tests do not exercise:
 * [Identity.toObject] mapping `publicKeys` through `it.toObject()`, and
 * `IdentityFactory.createFromBuffer` rebuilding each key through the
 * [IdentityPublicKey] `Map` constructor. This test pins BOTH directions on the
 * real 6-key shape, so a regression in either half fails here rather than
 * silently dropping bounds on read.
 *
 * The read half replicates `IdentityFactory.createFromBuffer` verbatim
 * (`decodeProtocolEntity` → stamp `protocolVersion` → `Identity(rawIdentity)`)
 * rather than instantiating the factory, which would drag in a whole
 * `DashPlatformProtocol` + `StateRepository` for no added coverage.
 */
class IdentityContractBoundsRoundTripTest {

    companion object {
        init {
            // KeyType/Purpose/SecurityLevel are SWIG enums whose constants call into JNI,
            // so the native library must be loaded before they are referenced.
            System.loadLibrary("sdklib")
        }
    }

    private val identityId = Identifier(ByteArray(32) { (it + 7).toByte() })

    /** Stands in for the dashpay contract id; only its 32 bytes matter here. */
    private val dashPayContractId = Identifier(ByteArray(32) { (it + 200).toByte() })

    private val contactRequestBounds
        get() = SingleContractDocumentType(dashPayContractId, "contactRequest")

    private fun keyData(seed: Int) = ByteArray(33) { (it + seed).toByte() }

    /**
     * The canonical registration key set the wallet commits on a fresh
     * identity: four auth/transfer keys plus the DashPay ENCRYPTION/DECRYPTION
     * pair, which are the only two carrying contract bounds.
     */
    private fun sixKeyIdentity() = Identity(
        identityId,
        listOf(
            IdentityPublicKey(
                0, KeyType.ECDSA_SECP256K1, Purpose.AUTHENTICATION, SecurityLevel.MASTER,
                null, keyData(1), false
            ),
            IdentityPublicKey(
                1, KeyType.ECDSA_SECP256K1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL,
                null, keyData(2), false
            ),
            IdentityPublicKey(
                2, KeyType.ECDSA_SECP256K1, Purpose.AUTHENTICATION, SecurityLevel.HIGH,
                null, keyData(3), false
            ),
            IdentityPublicKey(
                3, KeyType.ECDSA_SECP256K1, Purpose.TRANSFER, SecurityLevel.CRITICAL,
                null, keyData(4), false
            ),
            IdentityPublicKey(
                4, KeyType.ECDSA_SECP256K1, Purpose.ENCRYPTION, SecurityLevel.MEDIUM,
                contactRequestBounds, keyData(5), false
            ),
            IdentityPublicKey(
                5, KeyType.ECDSA_SECP256K1, Purpose.DECRYPTION, SecurityLevel.MEDIUM,
                contactRequestBounds, keyData(6), false
            )
        ),
        0L,
        0,
        ProtocolVersion.latestVersion
    )

    /** `IdentityFactory.createFromBuffer`, without needing the factory's dependencies. */
    private fun createFromBuffer(payload: ByteArray): Identity {
        val (protocolVersion, rawIdentity) = Factory.decodeProtocolEntity(payload)
        rawIdentity["protocolVersion"] = protocolVersion
        return Identity(rawIdentity)
    }

    @Test
    fun identityToBufferWithContractBoundKeysDoesNotThrow() {
        // The exact production failure: this threw while persisting the local
        // identity cache, stalling creation at IDENTITY_REGISTERING.
        val buffer = assertDoesNotThrow<ByteArray> { sixKeyIdentity().toBuffer() }
        assertTrue(buffer.isNotEmpty())
    }

    @Test
    fun identityRoundTripPreservesContractBoundsOnDashPayKeys() {
        val restored = createFromBuffer(sixKeyIdentity().toBuffer())

        assertEquals(identityId, restored.id)
        assertEquals(6, restored.publicKeys.size)

        // Keys 4 and 5 must come back as TYPED bounds, not null and not a raw Map.
        // Writing bounds but reading them back as null is the silent asymmetry
        // this assertion exists to prevent.
        for (keyId in listOf(4, 5)) {
            val key = restored.getPublicKeyById(keyId)
            assertInstanceOf(
                SingleContractDocumentType::class.java,
                key!!.contractBounds,
                "key $keyId lost its contract bounds across the buffer round-trip"
            )
            val bounds = key.contractBounds as SingleContractDocumentType
            assertEquals("contactRequest", bounds.documentType)
            assertEquals(dashPayContractId, bounds.identifier)
            assertEquals("documentType", bounds.type)
        }
    }

    @Test
    fun identityRoundTripLeavesUnboundKeysUnbound() {
        val restored = createFromBuffer(sixKeyIdentity().toBuffer())

        for (keyId in listOf(0, 1, 2, 3)) {
            assertNull(
                restored.getPublicKeyById(keyId)!!.contractBounds,
                "key $keyId gained contract bounds it never had"
            )
        }
    }

    @Test
    fun identityRoundTripPreservesEveryKeyIdentity() {
        val original = sixKeyIdentity()
        val restored = createFromBuffer(original.toBuffer())

        original.publicKeys.forEach { expected ->
            val actual = restored.getPublicKeyById(expected.id)
                ?: error("key ${expected.id} missing after round-trip")
            assertEquals(expected.type, actual.type, "key ${expected.id} type")
            assertEquals(expected.purpose, actual.purpose, "key ${expected.id} purpose")
            assertEquals(
                expected.securityLevel,
                actual.securityLevel,
                "key ${expected.id} securityLevel"
            )
            assertArrayEquals(expected.data, actual.data, "key ${expected.id} data")
        }
    }

    @Test
    fun identityWithSingleContractBoundsRoundTrips() {
        // The other ContractBounds variant, which has no documentType and whose
        // toObject() must therefore omit the key entirely.
        val identity = Identity(
            identityId,
            listOf(
                IdentityPublicKey(
                    0, KeyType.ECDSA_SECP256K1, Purpose.AUTHENTICATION, SecurityLevel.MASTER,
                    SingleContractBounds(dashPayContractId), keyData(1), false
                )
            ),
            0L,
            0,
            ProtocolVersion.latestVersion
        )

        val bounds = createFromBuffer(identity.toBuffer()).getPublicKeyById(0)!!.contractBounds
        assertInstanceOf(SingleContractBounds::class.java, bounds)
        assertEquals(dashPayContractId, bounds!!.identifier)
        assertEquals("singleContract", bounds.type)
    }
}
