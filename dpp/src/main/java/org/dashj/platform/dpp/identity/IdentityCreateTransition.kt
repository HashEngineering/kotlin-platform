/**
 * Copyright (c) 2020-present, Dash Core Team
 *
 * This source code is licensed under the MIT license found in the
 * COPYING file in the root directory of this source tree.
 */
package org.dashj.platform.dpp.identity

import org.bitcoinj.core.NetworkParameters
import org.dashj.platform.dpp.ProtocolVersion
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.statetransition.AssetLockProofFactory

class IdentityCreateTransition : IdentityStateTransition {

    val identityId: Identifier // base58
    val assetLockProof: AssetLockProof // base64
    val publicKeys: MutableList<IdentityPublicKey>
    /** returns id of created identity */
    override val modifiedDataIds: List<Identifier>
        get() = listOf(identityId)

    constructor(
        params: NetworkParameters,
        assetLock: AssetLockProof,
        publicKeys: List<IdentityPublicKey>,
        protocolVersion: Int = ProtocolVersion.latestVersion
    ) :
            super(params, Types.IDENTITY_CREATE, protocolVersion) {
        this.assetLockProof = assetLock
        this.identityId = assetLock.createIdentifier()
        this.publicKeys = publicKeys.toMutableList()
    }

    constructor(params: NetworkParameters, rawStateTransition: Map<String, Any?>) :
            super(params, rawStateTransition) {
        assetLockProof = AssetLockProofFactory.createAssetLockProofInstance(
            params,
            rawStateTransition["assetLockProof"] as Map<String, Any?>
        )
        publicKeys = (rawStateTransition["publicKeys"] as List<Any>).map {
                entry ->
            IdentityPublicKey(entry as MutableMap<String, Any>)
        }.toMutableList()
        identityId = assetLockProof.createIdentifier()
    }

    override fun toObject(skipSignature: Boolean, skipIdentifiersConversion: Boolean): MutableMap<String, Any?> {
        val map = super.toObject(skipSignature, skipIdentifiersConversion)
        map["assetLockProof"] = assetLockProof.toObject()
        map["publicKeys"] = publicKeys.map { it.toObject(skipSignature) }
        map.remove("signaturePublicKeyId")
        return map
    }

    override fun toJSON(skipSignature: Boolean): MutableMap<String, Any?> {
        val json = super.toJSON(skipSignature)
        json["assetLockProof"] = assetLockProof.toJSON()
        json["publicKeys"] = publicKeys.map { it.toJSON() }
        json.remove("signaturePublicKeyId")
        return json
    }

    fun addPublicKeys(identityPublicKeys: List<IdentityPublicKey>) = apply {
        publicKeys.addAll(identityPublicKeys)
    }

    fun getOwnerId(): Identifier {
        return identityId
    }
}
