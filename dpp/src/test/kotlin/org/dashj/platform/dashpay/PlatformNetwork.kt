/**
 * Copyright (c) 2020-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.platform.dashpay

import java.util.Date
import java.util.EnumSet
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.KeyChainGroup
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dashj.platform.sdk.platform.Platform
import org.junit.jupiter.api.AfterEach

open class PlatformNetwork {

    val platform = Platform(TestNet3Params.get())
    val seed = "domain major supply stove finger endorse crystal grape cinnamon syrup happy total"
    val wallet: Wallet = Wallet(
        platform.params,
        KeyChainGroup.builder(platform.params)
            .addChain(
                DeterministicKeyChain.builder()
                    .accountPath(DerivationPathFactory.get(platform.params).bip44DerivationPath(0))
                    .seed(DeterministicSeed(seed, null, "", Date().time))
                    .build()
            )
            .build()
    )
    val authenticationGroupExtension = AuthenticationGroupExtension(platform.params)
    init {
        authenticationGroupExtension.addKeyChains(
            platform.params, wallet.keyChainSeed,
            EnumSet.of(
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY,
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING,
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP,
                AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING
            )
        )
        wallet.addExtension(authenticationGroupExtension)
    }

    @AfterEach
    fun afterEachTest() {
        println(platform.client.reportNetworkStatus())
    }

    init {
        println("initializing platform")
        platform.useValidNodes()
    }
}
