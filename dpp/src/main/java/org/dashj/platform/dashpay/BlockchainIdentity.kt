/**
 * Copyright (c) 2020-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.platform.dashpay

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.base.Preconditions.checkState
import com.google.common.collect.ImmutableList
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Timer
import kotlin.concurrent.timerTask
import kotlinx.coroutines.delay
import org.bitcoinj.coinjoin.CoinJoinCoinSelector
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.EncryptedData
import org.bitcoinj.crypto.HDUtils
import org.bitcoinj.crypto.KeyCrypterAESCBC
import org.bitcoinj.crypto.KeyCrypterECDH
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.evolution.EvolutionContact
import org.bitcoinj.quorums.InstantSendLock
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.ZeroConfCoinSelector
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dashj.platform.wallet.TxMetadata
import org.dashj.platform.contracts.wallet.TxMetadataDocument
import org.dashj.platform.contracts.wallet.TxMetadataItem
import org.dashj.platform.dapiclient.MaxRetriesReachedException
import org.dashj.platform.dapiclient.model.DocumentQuery
import org.dashj.platform.dashpay.callback.*
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.document.DocumentsBatchTransition
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofException
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.dpp.statetransition.StateTransitionIdentitySigned
import org.dashj.platform.dpp.toBase58
import org.dashj.platform.dpp.toBase64
import org.dashj.platform.dpp.toHex
import org.dashj.platform.dpp.util.Cbor
import org.dashj.platform.dpp.util.Converters
import org.dashj.platform.sdk.*
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.sdk.platform.Platform
import org.slf4j.LoggerFactory
import java.math.BigInteger

class BlockchainIdentity {

    var platform: Platform
    var profiles: Profiles
    var params: NetworkParameters

    private constructor(platform: Platform) {
        this.params = platform.params
        this.platform = platform
        profiles = Profiles(platform)
    }

    companion object {
        const val BLOCKCHAIN_USERNAME_SALT = "BLOCKCHAIN_USERNAME_SALT"
        const val BLOCKCHAIN_USERNAME_STATUS = "BLOCKCHAIN_USERNAME_STATUS"
        const val BLOCKCHAIN_USERNAME_UNIQUE = "BLOCKCHAIN_USERNAME_UNIQUE"

        private val log = LoggerFactory.getLogger(BlockchainIdentity::class.java)
    }

    enum class RegistrationStatus {
        UNKNOWN,
        REGISTERING,
        REGISTERED,
        NOT_REGISTERED,
        RETRY
    }

    enum class UsernameStatus(val value: Int) {
        NOT_PRESENT(0),
        INITIAL(1),
        PREORDER_REGISTRATION_PENDING(2),
        PREORDERED(3),
        REGISTRATION_PENDING(4),
        CONFIRMED(5),
        TAKEN_ON_NETWORK(6);

        companion object {
            private val values = values()
            fun getByCode(code: Int): UsernameStatus {
                return values.filter { it.value == code }[0]
            }
        }
    }

    enum class IdentityKeyStatus {
        UNKNOWN,
        REGISTERED,
        REGISTERING,
        NOT_REGISTERED,
        REVOKED
    }

    enum class KeyIndexPurpose {
        MASTER,
        AUTHENTICATION,
        ENCRYPTION
    }

    lateinit var usernameStatuses: MutableMap<String, Any?>

    /** This is the unique identifier representing the blockchain identity. It is derived from the credit funding transaction credit burn UTXO */
    lateinit var uniqueId: Sha256Hash
    val uniqueIdString: String
        get() = uniqueId.toStringBase58()
    val uniqueIdData: ByteArray
        get() = uniqueId.bytes
    var uniqueIdentifier: Identifier = Identifier.from(Sha256Hash.ZERO_HASH)
        get() {
            if (field == Identifier.from(Sha256Hash.ZERO_HASH)) {
                field = Identifier.from(uniqueId)
            }
            return field
        }

    var identity: Identity? = null

    /** This is if the blockchain identity is present in wallets or not. If this is false then the blockchain identity is known for example from being a dashpay friend. */
    var isLocal: Boolean = false

    var wallet: Wallet? = null
    var authenticationGroup: AuthenticationGroupExtension? = null

    lateinit var lockedOutpoint: TransactionOutPoint
    val lockedOutpointData: ByteArray?
        get() = lockedOutpoint.bitcoinSerialize()

    var index: Int = 0

    // lateinit var usernames: List<String>

    var currentUsername: String? = null

    var accountLabel: String = "Default Account"
    var account: Int = 0

    val registrationFundingAddress: Address
        get() = Address.fromKey(wallet!!.params, registrationFundingPrivateKey)

    // var dashpayBioString: String

    lateinit var registrationStatus: RegistrationStatus

    lateinit var usernameSalts: MutableMap<String, ByteArray>

    var creditBalance: Coin = Coin.ZERO

    var activeKeyCount: Int = 0

    var totalKeyCount: Int = 0

    var keysCreated: Long = 0

    lateinit var keyInfo: MutableMap<Long, MutableMap<String, Any>>
    var currentMainKeyIndex: Int = 0
    var currentMainKeyType: KeyType = KeyType.ECDSA_SECP256K1
    var assetLockTransaction: AssetLockTransaction? = null
    lateinit var registrationFundingPrivateKey: ECKey

    // profile
    var lastProfileDocument: Document? = null

    constructor(platform: Platform, uniqueId: Sha256Hash) : this(platform) {
        Preconditions.checkArgument(uniqueId != Sha256Hash.ZERO_HASH, "uniqueId must not be zero")
        this.uniqueId = uniqueId
        this.isLocal = false
        this.keysCreated = 0
        this.currentMainKeyIndex = 0
        this.currentMainKeyType = KeyType.ECDSA_SECP256K1
        this.usernameStatuses = HashMap()
        this.keyInfo = HashMap()
        this.registrationStatus = RegistrationStatus.REGISTERED
    }

    constructor(platform: Platform, index: Int, wallet: Wallet, authenticationGroupExtension: AuthenticationGroupExtension) : this(
        platform
    ) {
        Preconditions.checkArgument(index != Int.MAX_VALUE && index != Int.MIN_VALUE, "index must be found")

        this.wallet = wallet
        this.authenticationGroup = authenticationGroupExtension
        this.isLocal = true
        this.keysCreated = 0
        this.currentMainKeyIndex = 0
        this.currentMainKeyType = KeyType.ECDSA_SECP256K1
        this.index = index
        this.usernameStatuses = HashMap()
        this.keyInfo = HashMap()
        this.registrationStatus = RegistrationStatus.UNKNOWN
        this.usernameSalts = HashMap()
    }

    constructor(
        platform: Platform,
        index: Int,
        lockedOutpoint: TransactionOutPoint,
        wallet: Wallet,
        authenticationGroupExtension: AuthenticationGroupExtension
    ) :
    this(platform, index, wallet, authenticationGroupExtension) {
        Preconditions.checkArgument(lockedOutpoint.hash != Sha256Hash.ZERO_HASH, "utxo must not be null")
        this.lockedOutpoint = lockedOutpoint
        this.uniqueId = Sha256Hash.twiceOf(lockedOutpoint.bitcoinSerialize())
    }

    constructor(
        platform: Platform,
        transaction: AssetLockTransaction,
        wallet: Wallet,
        authenticationGroupExtension: AuthenticationGroupExtension,
        registeredIdentity: Identity? = null
    ) : this(platform, transaction.getUsedDerivationPathIndex(0), transaction.lockedOutpoint, wallet, authenticationGroupExtension) {
        Preconditions.checkArgument(!transaction.assetLockPublicKey.isPubKeyOnly || transaction.assetLockPublicKey.isEncrypted)
        assetLockTransaction = transaction
        registrationFundingPrivateKey = transaction.assetLockPublicKey

        // see if the identity is registered.
        initializeIdentity(registeredIdentity, false)
    }

    private fun initializeIdentity(
        registeredIdentity: Identity? = null,
        throwException: Boolean
    ) {
        try {
            identity = registeredIdentity ?: platform.identities.get(uniqueIdString)
            registrationStatus = if (identity != null) {
                RegistrationStatus.REGISTERED
            } else {
                RegistrationStatus.NOT_REGISTERED
            }
        } catch (e: MaxRetriesReachedException) {
            // network is unavailable, so retry later
            log.info("unable to obtain identity from network.  Retry later.")
            registrationStatus = RegistrationStatus.RETRY
            if (throwException) {
                throw IllegalStateException("unable to obtain identity from Platform. Retry allowed", e)
            }
        } catch (e: Exception) {
            // swallow and leave the status as unknown
            registrationStatus = RegistrationStatus.UNKNOWN
            if (throwException) {
                throw IllegalStateException("unable to obtain identity from Platform: Reason unknown", e)
            }
        }
    }

    fun isRegistered(): Boolean {
        return registrationStatus == RegistrationStatus.REGISTERED
    }

    fun checkIdentity() {
        if (identity == null) {
            initializeIdentity(null, true)
        }
    }

    constructor(
        platform: Platform,
        transaction: AssetLockTransaction,
        usernameStatus: MutableMap<String, Any>,
        wallet: Wallet,
        authenticationGroupExtension: AuthenticationGroupExtension
    ) : this(platform, transaction, wallet, authenticationGroupExtension) {
        if (getUsernames().isNotEmpty()) {
            val usernameSalts = HashMap<String, ByteArray>()
            for (username in usernameStatus.keys) {
                val data = usernameStatus[username] as MutableMap<String, Any?>
                val salt = data[BLOCKCHAIN_USERNAME_SALT]
                if (salt != null) {
                    usernameSalts[username] = salt as ByteArray
                }
            }
            this.usernameStatuses = copyMap(usernameStatus)
            this.usernameSalts = usernameSalts
        }
    }

    private fun copyMap(map: MutableMap<String, Any>): MutableMap<String, Any?> {
        return Cbor.decode(Cbor.encode(map))
    }

    constructor(
        platform: Platform,
        index: Int,
        transaction: AssetLockTransaction,
        usernameStatus: MutableMap<String, Any>,
        credits: Coin,
        registrationStatus: RegistrationStatus,
        wallet: Wallet,
        authenticationGroupExtension: AuthenticationGroupExtension
    ) :
    this(platform, transaction, usernameStatus, wallet, authenticationGroupExtension) {
        creditBalance = credits
        this.registrationStatus = registrationStatus
    }

    // MARK: - Full Registration agglomerate

    fun createAssetLockTransaction(credits: Coin, keyParameter: KeyParameter?, useCoinJoin: Boolean, returnChange: Boolean): AssetLockTransaction {
        checkState(assetLockTransaction == null, "The credit funding transaction must not exist")
        checkState(
            registrationStatus == RegistrationStatus.UNKNOWN,
            "The identity must not be registered"
        )
        return createFundingTransaction(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING, credits, keyParameter, useCoinJoin, returnChange)
    }

    fun createTopupFundingTransaction(credits: Coin, keyParameter: KeyParameter?, useCoinJoin: Boolean, returnChange: Boolean): AssetLockTransaction {
        return createFundingTransaction(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP, credits, keyParameter, useCoinJoin, returnChange)
    }

    fun createInviteFundingTransaction(credits: Coin, keyParameter: KeyParameter?, useCoinJoin: Boolean, returnChange: Boolean): AssetLockTransaction {
        return createFundingTransaction(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING, credits, keyParameter, useCoinJoin, returnChange)
    }

    private fun createFundingTransaction(
        type: AuthenticationKeyChain.KeyChainType,
        credits: Coin,
        keyParameter: KeyParameter?,
        useCoinJoin: Boolean,
        returnChange: Boolean
    ): AssetLockTransaction {
        Preconditions.checkArgument(if (wallet!!.isEncrypted) keyParameter != null else true)
        val privateKey = authenticationGroup!!.currentKey(type)
        val request = SendRequest.creditFundingTransaction(wallet!!.params, privateKey as ECKey, credits)
        if (useCoinJoin) {
            // these are the settings for coinjoin
            request.coinSelector = CoinJoinCoinSelector(wallet!!)
            request.returnChange = returnChange
        } else {
            request.coinSelector = ZeroConfCoinSelector.get()
        }
        request.aesKey = keyParameter

        return wallet!!.sendCoinsOffline(request) as AssetLockTransaction
    }

    fun initializeAssetLockTransaction(creditFundingTransaction: AssetLockTransaction) {
        this.assetLockTransaction = creditFundingTransaction
        this.uniqueId = assetLockTransaction!!.identityId
        registrationStatus = RegistrationStatus.NOT_REGISTERED
    }

    fun registerIdentity(keyParameter: KeyParameter?, useISLock: Boolean = true) {
        if (useISLock) {
            try {
                registerIdentityWithISLock(keyParameter)
            } catch (e: InvalidInstantAssetLockProofException) {
                registerIdentityWithChainLock(keyParameter)
            }
        } else {
            registerIdentityWithChainLock(keyParameter)
        }
    }

    private fun registerIdentityWithChainLock(keyParameter: KeyParameter?) {
        checkState(
            registrationStatus != RegistrationStatus.REGISTERED,
            "The identity must not be registered"
        )
        checkState(assetLockTransaction != null, "The credit funding transaction must exist")

        val (privateKeyList, identityPublicKeys) = createIdentityPublicKeys(keyParameter)
        val privateKeys = privateKeyList.map { maybeDecryptKey(it, keyParameter)?.privKeyBytes!! }

        val signingKey = maybeDecryptKey(assetLockTransaction!!.assetLockPublicKey, keyParameter)

        val coreHeight = if (assetLockTransaction!!.confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
            assetLockTransaction!!.confidence.appearedAtChainHeight
        } else {
            // this is not supported, how can we get the height?
            TODO()
//            val txInfo = platform.client.getTransaction(assetLockTransaction!!.txId.toString())
//            txInfo?.height ?: -1
        }.toLong()

        identity = platform.identities.register(
            0,
            assetLockTransaction!!,
            coreHeight,
            signingKey!!,
            identityPublicKeys,
            signer = WalletSignerCallback(wallet!!, keyParameter)
        )

        registrationStatus = RegistrationStatus.REGISTERED

        finalizeIdentityRegistration(assetLockTransaction!!)

        registrationStatus = RegistrationStatus.REGISTERED
    }

    private fun registerIdentityWithISLock(keyParameter: KeyParameter?) {
        checkState(
            registrationStatus != RegistrationStatus.REGISTERED,
            "The identity must not be registered"
        )
        checkState(assetLockTransaction != null, "The credit funding transaction must exist")

        val (privateKeyList, identityPublicKeys) = createIdentityPublicKeys(keyParameter)

        val signingKey = maybeDecryptKey(assetLockTransaction!!.assetLockPublicKey, keyParameter)
        val privateKeys = privateKeyList.map {
            println(it)
            maybeDecryptKey(it, keyParameter)?.privKeyBytes!!
        }

        var instantLock: InstantSendLock? =
            wallet!!.context.instantSendManager?.getInstantSendLockByTxId(assetLockTransaction!!.txId)

        if (instantLock == null) {
            instantLock = assetLockTransaction!!.confidence?.instantSendlock

            val coreHeight = if (assetLockTransaction!!.confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
                assetLockTransaction!!.confidence.appearedAtChainHeight.toLong()
            } else {
                -1L
            }

            if (instantLock == null && coreHeight > 0) {
                identity = platform.identities.register(
                    0,
                    assetLockTransaction!!,
                    coreHeight,
                    signingKey!!,
                    identityPublicKeys,
                    signer = WalletSignerCallback(wallet!!, keyParameter)
                )
            } else if (instantLock != null) {
                identity = platform.identities.register(
                    0,
                    assetLockTransaction!!,
                    instantLock,
                    signingKey!!,
                    identityPublicKeys,
                    signer = WalletSignerCallback(wallet!!, keyParameter)
                )
            } else throw InvalidInstantAssetLockProofException("instantLock == null")
        } else {
            identity = platform.identities.register(
                0,
                assetLockTransaction!!,
                instantLock,
                signingKey!!,
                identityPublicKeys,
                signer = WalletSignerCallback(wallet!!, keyParameter)
            )
        }

        registrationStatus = RegistrationStatus.REGISTERED

        finalizeIdentityRegistration(assetLockTransaction!!)
    }

    private fun createIdentityPublicKeys(keyParameter: KeyParameter?): Pair<List<ECKey>, List<IdentityPublicKey>> {
        val identityPrivateKey = checkNotNull(
            privateKeyAtIndex(0, KeyType.ECDSA_SECP256K1, keyParameter)
        ) { "keys must exist" }

        val identityPrivateKey2 = checkNotNull(
            privateKeyAtIndex(1, KeyType.ECDSA_SECP256K1, keyParameter)
        ) { "keys must exist" }

        val masterKey = IdentityPublicKey(
            0,
            KeyType.ECDSA_SECP256K1,
            Purpose.AUTHENTICATION,
            SecurityLevel.MASTER,
            identityPrivateKey.pubKey,
            false
        )

        val highKey = IdentityPublicKey(
            1,
            KeyType.ECDSA_SECP256K1,
            Purpose.AUTHENTICATION,
            SecurityLevel.HIGH,
            identityPrivateKey2.pubKey,
            false
        )

        val encryptionKey = IdentityPublicKey(
            2,
            KeyType.ECDSA_SECP256K1,
            Purpose.ENCRYPTION,
            SecurityLevel.MEDIUM,
            identityPrivateKey.pubKey,
            false
        )
        return Pair(listOf(identityPrivateKey, identityPrivateKey2), listOf(masterKey, highKey))
    }

    fun recoverIdentity(creditFundingTransaction: AssetLockTransaction): Boolean {
        checkState(
            registrationStatus == RegistrationStatus.UNKNOWN,
            "The identity must not be registered"
        )

        identity =
            platform.identities.get(creditFundingTransaction.identityId.toStringBase58())
            ?: return false

        registrationStatus = RegistrationStatus.REGISTERED

        finalizeIdentityRegistration(creditFundingTransaction)

        return true
    }

    fun recoverIdentity(keyParameter: KeyParameter? = null): Boolean {
        val (key1, key2) = createIdentityPublicKeys(keyParameter)
        return recoverIdentity(key1[0].pubKeyHash)
    }

    fun recoverIdentity(pubKeyId: ByteArray): Boolean {
        checkState(
            registrationStatus == RegistrationStatus.UNKNOWN,
            "The identity must not be registered"
        )

        identity = platform.identities.getByPublicKeyHash(pubKeyId) ?: return false

        registrationStatus = RegistrationStatus.REGISTERED

        finalizeIdentityRegistration(identity!!.id)

        return true
    }

    private fun finalizeIdentityRegistration(identityId: Identifier) {
        this.registrationFundingPrivateKey =
            authenticationGroup!!.currentKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING) as ECKey
        val creditBurnIdentifier = Sha256Hash.wrap(identityId.toBuffer())
        finalizeIdentityRegistration(creditBurnIdentifier)
    }

    private fun finalizeIdentityRegistration(fundingTransaction: AssetLockTransaction) {
        this.assetLockTransaction = fundingTransaction
        this.registrationFundingPrivateKey = fundingTransaction.assetLockPublicKey
        this.lockedOutpoint = fundingTransaction.lockedOutpoint
        finalizeIdentityRegistration(fundingTransaction.identityId)
    }

    private fun finalizeIdentityRegistration(uniqueId: Sha256Hash) {
        this.uniqueId = uniqueId
        finalizeIdentityRegistration()
    }

    private fun finalizeIdentityRegistration() {
        if (isLocal) {
            saveInitial()
        }
    }

    // MARK: Registering
    // Preorder stage
    fun registerPreorderedSaltedDomainHashesForUsernames(usernames: List<String>, keyParameter: KeyParameter?) {
        val preorderDocuments = createPreorderDocuments(usernames)

        val signer = WalletSignerCallback(wallet!!, keyParameter)

        var i = 0
        val highIdentityPublicKey = identity!!.getFirstPublicKey(SecurityLevel.HIGH)
            ?: error("can't find a public key with HIGH security level")

        preorderDocuments.forEach { preorder ->
            // TODO: check for existing preorder
            log.info("checking for preorder {} with saltedDomainHash {}", preorder.ownerId, (preorder.data["saltedDomainHash"]!! as ByteArray).toBase64())
            if (platform.documents.get("dpns.preorder", DocumentQuery.builder().where(listOf("saltedDomainHash", "==", preorder.data["saltedDomainHash"]!!)).build()).isEmpty()) {
                val documentResult = dashsdk.platformMobilePutPutDocument(
                    preorder.toNative(),
                    preorder.dataContractId!!.toNative(),
                    preorder.type,
                    highIdentityPublicKey.toNative(),
                    BlockHeight(10000),
                    CoreBlockHeight(platform.coreBlockHeight),
                    BigInteger.valueOf(signer.signerCallback),
                    BigInteger.valueOf(platform.client.contextProviderFunction),
                    BigInteger.ZERO
                )
                val preorderDocument = documentResult.unwrap()
                log.info("preorder document: {}", preorderDocument.v0._0.id.bytes.toBase58())
            }

            val string = usernames[i++]
            var usernameStatusDictionary = usernameStatuses[string] as MutableMap<String, Any>
            if (usernameStatusDictionary == null) {
                usernameStatusDictionary = hashMapOf()
            }
            usernameStatusDictionary[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.PREORDER_REGISTRATION_PENDING
            usernameStatuses[string] = usernameStatusDictionary
        }

        val saltedDomainHashes = saltedDomainHashesForUsernames(usernames)

        for (username in saltedDomainHashes.keys) {
            val saltedDomainHashData = saltedDomainHashes[username] as ByteArray
            for (preorderTransition in preorderDocuments) {
                if ((preorderTransition.data["saltedDomainHash"] as ByteArray).contentEquals(saltedDomainHashData)) {
                    val usernameStatus = if (usernameStatuses.containsKey(username)) {
                        usernameStatuses[username] as MutableMap<String, Any>
                    } else {
                        HashMap()
                    }
                    usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.PREORDERED
                    usernameStatuses[username] = usernameStatus
                    saveUsername(username, UsernameStatus.PREORDERED, null, true)
                    platform.stateRepository.addValidDocument(preorderTransition.id)
                    platform.stateRepository.addValidPreorderSalt(saltForUsername(username, false), saltedDomainHashData)
                }
            }
        }
        saveUsernames(usernames, UsernameStatus.PREORDER_REGISTRATION_PENDING)
    }

    fun registerUsernameDomainsForUsernames(usernames: List<String>, keyParameter: KeyParameter?, alias: Boolean) {
        val domainDocuments = createDomainDocuments(usernames, alias)

        val signer = WalletSignerCallback(wallet!!, keyParameter)

        var i = 0
        domainDocuments.forEach { domain ->
            val document = broadcastDomainDocument(domain, signer)

            log.info("domain doc id: {}", Identifier(document.v0._0.id.bytes))

            val string = usernames[i++]
            var usernameStatusDictionary = usernameStatuses[string] as MutableMap<String, Any>
            if (usernameStatusDictionary == null) {
                usernameStatusDictionary = HashMap<String, Any>()
            }
            usernameStatusDictionary[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.REGISTRATION_PENDING
            usernameStatuses[string] = usernameStatusDictionary
        }

       saveUsernames(usernames, UsernameStatus.REGISTRATION_PENDING)

        val usernamesLeft = ArrayList(usernames)
        for (username in usernames) {
            val normalizedName = username.toLowerCase()
            for (nameDocumentTransition in domainDocuments) {
                if (nameDocumentTransition.data["normalizedLabel"] == normalizedName) {
                    val usernameStatus = if (usernameStatuses.containsKey(username)) {
                        usernameStatuses[username] as MutableMap<String, Any>
                    } else {
                        HashMap()
                    }
                    usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.CONFIRMED
                    usernameStatus[BLOCKCHAIN_USERNAME_UNIQUE] = Names.isUniqueIdentity(nameDocumentTransition)
                    usernameStatuses[username] = usernameStatus
                    saveUsername(username, UsernameStatus.CONFIRMED, null, true)
                    usernamesLeft.remove(username)
                    platform.stateRepository.addValidDocument(nameDocumentTransition.id)

                    val salt = saltForUsername(username, false)
                    val saltedDomainHash = Names.getSaltedDomainHashBytes(salt, "$username.${Names.DEFAULT_PARENT_DOMAIN}")

                    val preorderDocuments = platform.documents.get(
                        Names.DPNS_PREORDER_DOCUMENT,
                        DocumentQuery.builder()
                            .where("saltedDomainHash", "==", saltedDomainHash)
                            .build()
                    )
                    if (preorderDocuments.isNotEmpty()) {
                        deleteDocument(Names.DPNS_PREORDER_DOCUMENT, preorderDocuments.first().id, keyParameter)
                    }
                }
            }
        }
    }

    private fun broadcastDomainDocument(
        domain: Document,
        signer: WalletSignerCallback
    ): org.dashj.platform.sdk.Document {
        var error = ""
        val highIdentityPublicKey = identity!!.getFirstPublicKey(SecurityLevel.HIGH)
            ?: error("can't find a public key with HIGH security level")

        for (i in 0 .. 2) {
            val document_result = dashsdk.platformMobilePutPutDocument(
                domain.toNative(),
                domain.dataContractId!!.toNative(),
                domain.type,
                highIdentityPublicKey.toNative(),
                BlockHeight(10000),
                CoreBlockHeight(platform.coreBlockHeight),
                BigInteger.valueOf(signer.signerCallback),
                BigInteger.valueOf(platform.client.contextProviderFunction),
                BigInteger.ZERO
            )
            try {
                return document_result.unwrap()
            } catch (e: Exception) {
                // swallow
                error = e.toString()
                error(error)
                /*if (!error.contains("preorderDocument was not found") &&
                    !error.contains("Protocol error: unknown version error result did not have metadata"))
                    throw e*/
            }
        }
        throw IllegalStateException(error)
    }

    fun removePreorders(keyParameter: KeyParameter? = null) {
        for (usernameSalt in usernameSalts.values) {
            val preorderDocuments = platform.documents.get(
                Names.DPNS_DOMAIN_DOCUMENT,
                DocumentQuery.builder()
                    .where("saltedDomainHash", "==", usernameSalt)
                    .build()
            )

            // TODO: optimize by deleting all documents in a single state transition
            for (preorder in preorderDocuments) {
                deleteDocument(Names.DPNS_PREORDER_DOCUMENT, preorder.id, keyParameter)
            }
        }
    }

    /**
     * Recover all usernames and preorder data associated with the identity
     */
    fun recoverUsernames() {
        checkState(
            registrationStatus == RegistrationStatus.REGISTERED,
            "Identity must be registered before recovering usernames"
        )

        val nameDocuments = arrayListOf<Document>()
        nameDocuments.addAll(platform.names.getByOwnerId(uniqueIdentifier))
        nameDocuments.addAll(platform.names.getByUserIdAlias(uniqueIdentifier))
        val usernames = ArrayList<String>()

        for (nameDocument in nameDocuments) {
            val username = nameDocument.data["normalizedLabel"] as String
            var usernameStatusDictionary = HashMap<String, Any>()
            usernameStatusDictionary[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.CONFIRMED
            usernameStatusDictionary[BLOCKCHAIN_USERNAME_UNIQUE] = Names.isUniqueIdentity(nameDocument)
            usernameStatuses[username] = usernameStatusDictionary
            usernameSalts[username] = Converters.byteArrayFromBase64orByteArray(nameDocument.data["preorderSalt"]!!)
            usernameStatusDictionary[BLOCKCHAIN_USERNAME_SALT] = usernameSalts[username] as ByteArray
            usernames.add(username)
        }
        currentUsername = usernames.firstOrNull()
        saveUsernames(usernames, UsernameStatus.CONFIRMED)
    }

    //

    // MARK: Username Helpers

    fun saltForUsername(username: String, saveSalt: Boolean): ByteArray {
        var salt: ByteArray
        if (statusOfUsername(username) == UsernameStatus.INITIAL || !(usernameSalts.containsKey(username))) {
            salt = ECKey().privKeyBytes
            usernameSalts[username] = salt
            if (saveSalt) {
                saveUsername(username, statusOfUsername(username), salt, true)
            }
        } else {
            salt = usernameSalts[username]!!
        }
        return salt
    }

    fun saltedDomainHashesForUsernames(usernames: List<String>): MutableMap<String, ByteArray> {
        val mSaltedDomainHashes = HashMap<String, ByteArray>()
        for (unregisteredUsername in usernames) {
            val salt = saltForUsername(unregisteredUsername, true)
            val fullUsername = if (unregisteredUsername.contains(".")) {
                unregisteredUsername
            } else {
                unregisteredUsername + "." + Names.DEFAULT_PARENT_DOMAIN
            }
            val saltedDomainHashData = Names.getSaltedDomainHashBytes(salt, fullUsername)
            mSaltedDomainHashes[unregisteredUsername] = saltedDomainHashData
            usernameSalts[unregisteredUsername] = salt // is this required?
        }
        return mSaltedDomainHashes
    }

    // MARK: Documents

    fun createPreorderDocuments(unregisteredUsernames: List<String>): List<Document> {
        val usernamePreorderDocuments = ArrayList<Document>()
        checkIdentity()
        for (saltedDomainHash in saltedDomainHashesForUsernames(unregisteredUsernames).values) {
            val document = platform.names.createPreorderDocument(Sha256Hash.wrap(saltedDomainHash), identity!!)
            usernamePreorderDocuments.add(document)
        }
        return usernamePreorderDocuments
    }

    fun createDomainDocuments(unregisteredUsernames: List<String>, alias: Boolean): List<Document> {
        val usernameDomainDocuments = ArrayList<Document>()
        checkIdentity()
        for (username in saltedDomainHashesForUsernames(unregisteredUsernames).keys) {
            val isUniqueIdentity = !alias || (
                usernameDomainDocuments.isEmpty() && getUsernamesWithStatus(UsernameStatus.CONFIRMED).isEmpty())
            val document =
                platform.names.createDomainDocument(identity!!, username, usernameSalts[username]!!, isUniqueIdentity)
            usernameDomainDocuments.add(document)
        }
        return usernameDomainDocuments
    }

    // MARK: Transitions

    fun createPreorderTransition(unregisteredUsernames: List<String>): DocumentsBatchTransition? {
        val usernamePreorderDocuments = createPreorderDocuments(unregisteredUsernames)
        if (usernamePreorderDocuments.isEmpty()) return null
        val transitionMap = hashMapOf<String, List<Document>?>(
            "create" to usernamePreorderDocuments
        )
        //return platform.dpp.document.createStateTransition(transitionMap)
        return null
    }

    fun createDomainTransition(unregisteredUsernames: List<String>, alias: Boolean): DocumentsBatchTransition? {
        val usernameDomainDocuments = createDomainDocuments(unregisteredUsernames, alias)
        if (usernameDomainDocuments.isEmpty()) return null
        val transitionMap = hashMapOf<String, List<Document>?>(
            "create" to usernameDomainDocuments
        )
        //return platform.dpp.document.createStateTransition(transitionMap)
        return null
    }

    // MARK: Usernames

    fun addUsername(username: String, status: UsernameStatus, save: Boolean) {
        val map = HashMap<String, UsernameStatus>()
        map[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.INITIAL
        usernameStatuses[username] = map
        currentUsername = username

        if (save) {
            saveNewUsername(username, UsernameStatus.INITIAL)
        }
        if (registrationStatus == RegistrationStatus.REGISTERED && status != UsernameStatus.CONFIRMED) {
            // do we trigger a listener here?
        }
    }

    fun addUsername(username: String, save: Boolean = true) {
        addUsername(username, UsernameStatus.INITIAL, save)
    }

    fun statusOfUsername(username: String): UsernameStatus {
        return if (usernameStatuses.containsKey(username)) {
            (usernameStatuses[username] as MutableMap<String, Any>)[BLOCKCHAIN_USERNAME_STATUS] as? UsernameStatus ?: UsernameStatus.INITIAL
        } else UsernameStatus.NOT_PRESENT
    }

    fun getUsernames(): List<String> {
        return usernameStatuses.keys.toList()
    }

    fun getUsernamesWithStatus(usernameStatus: UsernameStatus): MutableList<String> {
        val usernames = ArrayList<String>()
        for (username in usernameStatuses.keys) {
            val usernameInfo = usernameStatuses[username] as MutableMap<String, Any?>
            val status = usernameInfo[BLOCKCHAIN_USERNAME_STATUS] as? UsernameStatus ?: UsernameStatus.INITIAL
            if (status == usernameStatus) {
                usernames.add(username)
            }
        }
        return usernames
    }

    fun getUniqueUsername(): String {
        for (username in usernameStatuses.keys) {
            val usernameInfo = usernameStatuses[username] as MutableMap<String, Any?>
            val isUnique = usernameInfo[BLOCKCHAIN_USERNAME_UNIQUE] as Boolean
            if (isUnique) {
                return username
            }
        }
        throw IllegalStateException("There is no unique username")
    }

    fun getAliasList(): List<String> {
        val usernames = arrayListOf<String>()
        for (username in usernameStatuses.keys) {
            val usernameInfo = usernameStatuses[username] as MutableMap<String, Any?>
            val isUnique = usernameInfo[BLOCKCHAIN_USERNAME_UNIQUE] as Boolean

            if (!isUnique) {
                usernames.add(username)
            }
        }
        return usernames
    }

    fun getUnregisteredUsernames(): MutableList<String> {
        return getUsernamesWithStatus(UsernameStatus.INITIAL)
    }

    fun preorderedUsernames(): MutableList<String> {
        return getUsernamesWithStatus(UsernameStatus.PREORDERED)
    }

    // MARK: - Signing and Encryption

    fun signStateTransition(
        transition: StateTransitionIdentitySigned,
        keyIndex: Int,
        signingAlgorithm: KeyType,
        keyParameter: KeyParameter? = null
    ) {
        val privateKey = maybeDecryptKey(keyIndex, signingAlgorithm, keyParameter)
        checkState(privateKey != null, "The private key should exist")

        transition.sign(getIdentityPublicKeyByPurpose(KeyIndexPurpose.AUTHENTICATION), privateKey!!.privateKeyAsHex)
    }

    /**
     * Decrypts the key at the keyIndex if necessary using the keyParameter
     * @param keyIndex Int
     * @param signingAlgorithm KeyType
     * @param keyParameter KeyParameter?
     * @return ECKey?
     */
    private fun maybeDecryptKey(
        keyIndex: Int,
        signingAlgorithm: KeyType,
        keyParameter: KeyParameter?
    ): ECKey? {
        var privateKey = privateKeyAtIndex(keyIndex, signingAlgorithm, keyParameter)
        if (privateKey!!.isEncrypted) {
            privateKey = privateKey.decrypt(wallet!!.keyCrypter, keyParameter)
        }
        return privateKey
    }

    private fun maybeDecryptKey(
        encryptedPrivateKey: ECKey,
        keyParameter: KeyParameter?
    ): ECKey? {
        var privateKey = encryptedPrivateKey
        if (encryptedPrivateKey!!.isEncrypted) {
            privateKey = encryptedPrivateKey.decrypt(wallet!!.keyCrypter, keyParameter)
        }
        return privateKey
    }

    fun getPrivateKeyByPurpose(purpose: KeyIndexPurpose, keyParameter: KeyParameter?): ECKey {
        return maybeDecryptKey(purpose.ordinal, identity!!.getPublicKeyById(purpose.ordinal)!!.type, keyParameter)!!
    }
    fun getIdentityPublicKeyByPurpose(purpose: KeyIndexPurpose): IdentityPublicKey {
        return identity!!.getPublicKeyById(purpose.ordinal)!!
    }

    fun signStateTransition(transition: StateTransitionIdentitySigned, keyParameter: KeyParameter?) {
        checkIdentity()
        val identityPublicKey = getIdentityPublicKeyByPurpose(KeyIndexPurpose.AUTHENTICATION)
        return signStateTransition(
            transition,
            KeyIndexPurpose.AUTHENTICATION.ordinal,
            identityPublicKey.type,
            keyParameter
        )
    }

    fun derivationPathForType(type: KeyType): ImmutableList<ChildNumber>? {
        if (isLocal) {
            if (type == KeyType.ECDSA_SECP256K1) {
                return DerivationPathFactory(wallet!!.params).blockchainIdentityECDSADerivationPath()
            } else if (type == KeyType.BLS12_381) {
                return DerivationPathFactory(wallet!!.params).blockchainIdentityBLSDerivationPath()
            }
        }
        return null
    }

    /**
     * Get a decrypted private key at index for the specified key type
     *
     * @param index the index of the key to obtain see [KeyIndexPurpose]
     * @param type the type of key to obtain
     * @param keyParameter the encryption key of the wallet
     * @return the key that matches the input parameters or null
     */
    private fun privateKeyAtIndex(index: Int, type: KeyType, keyParameter: KeyParameter?): ECKey? {
        checkState(isLocal, "this must own a wallet")

        when (type) {
            KeyType.ECDSA_SECP256K1 -> {
                val authenticationChain = authenticationGroup!!.getKeyChain(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY)
                // decrypt keychain
                val decryptedChain = if (wallet!!.isEncrypted) {
                    authenticationChain.toDecrypted(keyParameter)
                } else {
                    authenticationChain
                }
                val key = decryptedChain.getKey(index) // watchingKey
                checkState(key.path.last().isHardened)
                return key as ECKey?
            }
            else -> throw IllegalArgumentException("$type is not supported")
        }
    }

    @VisibleForTesting
    fun privateKeyAtPath(
        rootIndex: Int,
        childNumber: ChildNumber,
        subIndex: Int,
        type: KeyType,
        keyParameter: KeyParameter?
    ): ECKey? {
        checkState(isLocal, "this must own a wallet")

        when (type) {
            KeyType.ECDSA_SECP256K1 -> {
                val authenticationChain = authenticationGroup!!.getKeyChain(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY)
                // decrypt keychain
                val decryptedChain = if (wallet!!.isEncrypted) {
                    authenticationChain.toDecrypted(keyParameter)
                } else {
                    authenticationChain
                }
                val fullPath = ImmutableList.builder<ChildNumber>().addAll(authenticationChain.accountPath)
                    .add(ChildNumber(rootIndex, true))
                    .add(childNumber) // this should be hardened
                    .add(ChildNumber(subIndex, true))
                    .build()
                val key = decryptedChain.getKeyByPath(fullPath, true) // watchingKey
                checkState(key.path.last().isHardened)
                return key as ECKey?
            }
            else -> throw IllegalArgumentException("$type is not supported")
        }
    }

    // MARK: Saving

    fun saveInitial() {
        /*
            save the following to the Room Database
            - uniqueId
            - isLocal
            - if (isLocal) {
                creditFundingTransaction

                }
             - network id (wallet.params.id)
             - usernameStatuses
             - DashPay stuff...
         */
        // TODO()
    }

    fun save() {
        // save updates to creditBalance, registrationStatus, type
        // send notifications for the items that were updated
    }

    fun saveUsernames(usernames: List<String>, status: UsernameStatus) {
        for (username in usernames) {
            saveUsername(username, status, null, false)
        }
    }

    fun saveUsernamesToStatuses(dictionary: MutableMap<String, UsernameStatus>) {
        for (username in dictionary.keys) {
            val status = dictionary[username]
            saveUsername(username, status!!, null, false)
        }
    }

    fun saveUsername(username: String, status: UsernameStatus, salt: ByteArray?, commitSave: Boolean) {
        // save the username information to the Room database
        // TODO: Do we actually need this method
    }

    fun saveNewUsername(username: String, status: UsernameStatus) {
        // TODO: Do we actually need this method
        saveUsername(username, status, null, true)
    }

    /**
     * This method will determine if the associated identity exists by making a platform query
     * the specified number of times with the specified delay between attempts.  If the identity
     * exists, then the onSuccess method of the callback is invoked.  Otherwise the onTimeout method
     * is invoked.
     * @param retryCount Int The number of times to try to determine if an identity exists
     * @param delayMillis Long The delay between attempts to determine if an identity exists
     * @param retryDelayType RetryDelayType
     * @param callback RegisterIdentityCallback
     */
//    @Deprecated("v18 makes this function obsolete")
//    fun watchIdentity(
//        retryCount: Int,
//        delayMillis: Long,
//        retryDelayType: RetryDelayType,
//        callback: RegisterIdentityCallback
//    ) {
//        val identityQuery = MulticallQuery(
//            object : MulticallMethod<Identity?> {
//                override fun execute(): Identity? {
//                    return platform.identities.get(uniqueIdString)
//                }
//            },
//            MulticallQuery.Companion.CallType.MAJORITY_FOUND
//        )
//
//        // have more than half the nodes returned success and do they all agree?
//        if (identityQuery.queryFound()) {
//            identity = identityQuery.getResult()
//            registrationStatus = RegistrationStatus.REGISTERED
//            platform.stateRepository.addValidIdentity(identity!!.id)
//            save()
//            callback.onComplete(uniqueIdString)
//        } else {
//            if (retryCount > 0) {
//                Timer("monitorBlockchainIdentityStatus", false).schedule(
//                    timerTask {
//                        val nextDelay = delayMillis * when (retryDelayType) {
//                            RetryDelayType.SLOW20 -> 5 / 4
//                            RetryDelayType.SLOW50 -> 3 / 2
//                            else -> 1
//                        }
//                        watchIdentity(retryCount - 1, nextDelay, retryDelayType, callback)
//                    },
//                    delayMillis
//                )
//            } else callback.onTimeout()
//        }
//    }
//
//    @Deprecated("v18 makes this function obsolete")
//    suspend fun watchIdentity(
//        retryCount: Int,
//        delayMillis: Long,
//        retryDelayType: RetryDelayType
//    ): String? {
//        val identityQuery = MulticallQuery(
//            object : MulticallMethod<Identity?> {
//                override fun execute(): Identity? {
//                    return platform.identities.get(uniqueIdString)
//                }
//            },
//            MulticallQuery.Companion.CallType.MAJORITY_FOUND
//        )
//
//        if (identityQuery.queryFound()) {
//            identity = identityQuery.getResult()
//            registrationStatus = RegistrationStatus.REGISTERED
//            platform.stateRepository.addValidIdentity(identity!!.id)
//            save()
//            return uniqueIdString
//        } else {
//            if (retryCount > 0) {
//                val nextDelay = delayMillis * when (retryDelayType) {
//                    RetryDelayType.SLOW20 -> 5 / 4
//                    RetryDelayType.SLOW50 -> 3 / 2
//                    else -> 1
//                }
//                delay(nextDelay)
//                return watchIdentity(retryCount - 1, nextDelay, retryDelayType)
//            }
//        }
//        return null
//    }

    /**
     * This method will determine if the given preordered names exist by making a platform query
     * the specified number of times with the specified delay between attempts.  If the preorders
     * exist, then the onSuccess method of the callback is invoked.  Otherwise the onTimeout method
     * is invoked.
     * @param saltedDomainHashes Map<String, ByteArray> Map of usernames and salted domain hashes
     * @param retryCount Int
     * @param delayMillis Long
     * @param retryDelayType RetryDelayType
     * @param callback RegisterPreorderCallback
     */
    @Deprecated("v18 makes this function obsolete")
    fun watchPreorder(
        saltedDomainHashes: Map<String, ByteArray>,
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType,
        callback: RegisterPreorderCallback
    ) {
        val query = DocumentQuery.Builder()
            .where(
                listOf(
                    "saltedDomainHash",
                    "in",
                    saltedDomainHashes.map {
                        it.value
                    }
                )
            ).orderBy("saltedDomainHash").build()
        val preorderDocuments = platform.documents.get(Names.DPNS_PREORDER_DOCUMENT, query)

        if (preorderDocuments.isNotEmpty()) {
            val usernamesLeft = HashMap(saltedDomainHashes)
            for (username in saltedDomainHashes.keys) {
                val saltedDomainHashData = saltedDomainHashes[username] as ByteArray
                for (preorderDocument in preorderDocuments) {
                    if ((preorderDocument.data["saltedDomainHash"] as ByteArray).contentEquals(saltedDomainHashData)) {
                        var usernameStatus = if (usernameStatuses.containsKey(username)) {
                            usernameStatuses[username] as MutableMap<String, Any>
                        } else {
                            HashMap()
                        }
                        usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.PREORDERED
                        usernameStatuses[username] = usernameStatus
                        saveUsername(username, UsernameStatus.PREORDERED, null, true)
                        usernamesLeft.remove(username)
                    }
                }
            }
            if (usernamesLeft.size > 0 && retryCount > 0) {
                val saltedDomainHashesLeft = saltedDomainHashes.filter { usernamesLeft.containsKey(it.key) }
                Timer("monitorBlockchainIdentityStatus", false).schedule(
                    timerTask {
                        val nextDelay = delayMillis * when (retryDelayType) {
                            RetryDelayType.SLOW20 -> 5 / 4
                            RetryDelayType.SLOW50 -> 3 / 2
                            else -> 1
                        }
                        watchPreorder(
                            saltedDomainHashesLeft,
                            retryCount - 1,
                            nextDelay,
                            retryDelayType,
                            callback
                        )
                    },
                    delayMillis
                )
            } else if (usernamesLeft.size > 0) {
                val saltedDomainHashesLeft = saltedDomainHashes.filter { usernamesLeft.containsKey(it.key) }
                callback.onTimeout(saltedDomainHashesLeft.keys.toList())
            } else {
                callback.onComplete(saltedDomainHashes.keys.toList())
            }
        } else {
            if (retryCount > 0) {
                Timer("monitorForDPNSPreorderSaltedDomainHashes", false).schedule(
                    timerTask {
                        val nextDelay = delayMillis * when (retryDelayType) {
                            RetryDelayType.SLOW20 -> 5 / 4
                            RetryDelayType.SLOW50 -> 3 / 2
                            else -> 1
                        }
                        watchPreorder(
                            saltedDomainHashes,
                            retryCount - 1,
                            nextDelay,
                            retryDelayType,
                            callback
                        )
                    },
                    delayMillis
                )
            } else {
                callback.onTimeout(saltedDomainHashes.keys.toList())
            }
        }
    }

    @Deprecated("v18 makes this function obsolete")
    suspend fun watchPreorder(
        saltedDomainHashes: Map<String, ByteArray>,
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType
    ): Pair<Boolean, List<String>> {
        val query = DocumentQuery.Builder()
            .where(
                listOf(
                    "saltedDomainHash",
                    "in",
                    saltedDomainHashes.map { it.value }
                )
            ).build()

        val preorderDocuments = platform.documents.get(Names.DPNS_PREORDER_DOCUMENT, query)

        if (preorderDocuments != null && preorderDocuments.isNotEmpty()) {
            val usernamesLeft = HashMap(saltedDomainHashes)
            for (username in saltedDomainHashes.keys) {
                val saltedDomainHashData = saltedDomainHashes[username] as ByteArray
                for (preorderDocument in preorderDocuments) {
                    if ((preorderDocument.data["saltedDomainHash"] as ByteArray).contentEquals(saltedDomainHashData)) {
                        var usernameStatus = if (usernameStatuses.containsKey(username)) {
                            usernameStatuses[username] as MutableMap<String, Any>
                        } else {
                            HashMap()
                        }
                        usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.PREORDERED
                        usernameStatuses[username] = usernameStatus
                        saveUsername(username, UsernameStatus.PREORDERED, null, true)
                        usernamesLeft.remove(username)
                    }
                }
            }
            if (usernamesLeft.size > 0 && retryCount > 0) {
                val saltedDomainHashesLeft = saltedDomainHashes.filter { usernamesLeft.containsKey(it.key) }

                val nextDelay = delayMillis * when (retryDelayType) {
                    RetryDelayType.SLOW20 -> 5 / 4
                    RetryDelayType.SLOW50 -> 3 / 2
                    else -> 1
                }
                delay(nextDelay)
                return watchPreorder(
                    saltedDomainHashesLeft,
                    retryCount - 1,
                    nextDelay,
                    retryDelayType
                )
            } else if (usernamesLeft.size > 0) {
                val saltedDomainHashesLeft = saltedDomainHashes.filter { usernamesLeft.containsKey(it.key) }
                return Pair(false, saltedDomainHashesLeft.keys.toList())
            } else {
                return Pair(true, saltedDomainHashes.keys.toList())
            }
        } else {
            if (retryCount > 0) {
                val nextDelay = delayMillis * when (retryDelayType) {
                    RetryDelayType.SLOW20 -> 5 / 4
                    RetryDelayType.SLOW50 -> 3 / 2
                    else -> 1
                }
                delay(nextDelay)
                return watchPreorder(
                    saltedDomainHashes,
                    retryCount - 1,
                    nextDelay,
                    retryDelayType
                )
            } else {
                return Pair(false, saltedDomainHashes.keys.toList())
            }
        }
        // throw exception or return false
        return Pair(false, saltedDomainHashes.keys.toList())
    }

    /**
     * This method will determine if the given usernames exist by making a platform query
     * the specified number of times with the specified delay between attempts.  If the usernames
     * exist, then the onSuccess method of the callback is invoked.  Otherwise the onTimeout method
     * is invoked.
     * @param usernames List<String>
     * @param retryCount Int
     * @param delayMillis Long
     * @param retryDelayType RetryDelayType
     * @param callback RegisterNameCallback
     */
    @Deprecated("v18 makes this function obsolete")
    fun watchUsernames(
        usernames: List<String>,
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType,
        callback: RegisterNameCallback
    ) {
        val query = DocumentQuery.Builder()
            .where("normalizedParentDomainName", "==", Names.DEFAULT_PARENT_DOMAIN)
            .where(listOf("normalizedLabel", "in", usernames.map { it.toLowerCase() }))
            .orderBy("normalizedParentDomainName")
            .orderBy("normalizedLabel")
            .build()
        val nameDocuments = platform.documents.get(Names.DPNS_DOMAIN_DOCUMENT, query)

        if (nameDocuments.isNotEmpty()) {
            val usernamesLeft = ArrayList(usernames)
            for (username in usernames) {
                val normalizedName = username.toLowerCase()
                for (nameDocument in nameDocuments) {
                    if (nameDocument.data["normalizedLabel"] == normalizedName) {
                        var usernameStatus = if (usernameStatuses.containsKey(username)) {
                            usernameStatuses[username] as MutableMap<String, Any>
                        } else {
                            HashMap()
                        }
                        usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.CONFIRMED
                        usernameStatus[BLOCKCHAIN_USERNAME_UNIQUE] = Names.isUniqueIdentity(nameDocument)
                        usernameStatuses[username] = usernameStatus
                        saveUsername(username, UsernameStatus.CONFIRMED, null, true)
                        usernamesLeft.remove(username)
                    }
                }
            }
            if (usernamesLeft.size > 0 && retryCount > 0) {
                Timer("monitorForDPNSUsernames", false).schedule(
                    timerTask {
                        val nextDelay = delayMillis * when (retryDelayType) {
                            RetryDelayType.SLOW20 -> 5 / 4
                            RetryDelayType.SLOW50 -> 3 / 2
                            else -> 1
                        }
                        watchUsernames(usernamesLeft, retryCount - 1, nextDelay, retryDelayType, callback)
                    },
                    delayMillis
                )
            } else if (usernamesLeft.size > 0) {
                callback.onTimeout(usernamesLeft)
            } else {
                callback.onComplete(usernames)
            }
        } else {
            if (retryCount > 0) {
                Timer("monitorBlockchainIdentityStatus", false).schedule(
                    timerTask {
                        val nextDelay = delayMillis * when (retryDelayType) {
                            RetryDelayType.SLOW20 -> 5 / 4
                            RetryDelayType.SLOW50 -> 3 / 2
                            else -> 1
                        }
                        watchUsernames(usernames, retryCount - 1, nextDelay, retryDelayType, callback)
                    },
                    delayMillis
                )
            } else {
                callback.onTimeout(usernames)
            }
        }
    }

    @Deprecated("v18 makes this function obsolete")
    suspend fun watchUsernames(
        usernames: List<String>,
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType
    ): Pair<Boolean, List<String>> {
        val query = DocumentQuery.Builder()
            .where("normalizedParentDomainName", "==", Names.DEFAULT_PARENT_DOMAIN)
            .where(listOf("normalizedLabel", "in", usernames.map { "${it.toLowerCase()}" })).build()
        val nameDocuments = platform.documents.get(Names.DPNS_DOMAIN_DOCUMENT, query)

        if (nameDocuments.isNotEmpty()) {
            val usernamesLeft = ArrayList(usernames)
            for (username in usernames) {
                val normalizedName = username.toLowerCase()
                for (nameDocument in nameDocuments) {
                    if (nameDocument.data["normalizedLabel"] == normalizedName) {
                        val usernameStatus = if (usernameStatuses.containsKey(username)) {
                            usernameStatuses[username] as MutableMap<String, Any>
                        } else {
                            HashMap()
                        }
                        usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = UsernameStatus.CONFIRMED
                        usernameStatus[BLOCKCHAIN_USERNAME_UNIQUE] = Names.isUniqueIdentity(nameDocument)
                        usernameStatuses[username] = usernameStatus
                        saveUsername(username, UsernameStatus.CONFIRMED, null, true)
                        usernamesLeft.remove(username)
                    }
                }
            }
            if (usernamesLeft.size > 0 && retryCount > 0) {
                val nextDelay = delayMillis * when (retryDelayType) {
                    RetryDelayType.SLOW20 -> 5 / 4
                    RetryDelayType.SLOW50 -> 3 / 2
                    else -> 1
                }
                delay(nextDelay)
                return watchUsernames(usernamesLeft, retryCount - 1, nextDelay, retryDelayType)
            } else if (usernamesLeft.size > 0) {
                return Pair(false, usernamesLeft)
            } else {
                return Pair(true, usernames)
            }
        } else {
            if (retryCount > 0) {
                val nextDelay = delayMillis * when (retryDelayType) {
                    RetryDelayType.SLOW20 -> 5 / 4
                    RetryDelayType.SLOW50 -> 3 / 2
                    else -> 1
                }
                delay(nextDelay)
                return watchUsernames(usernames, retryCount - 1, nextDelay, retryDelayType)
            } else {
                return Pair(false, usernames)
            }
        }
        // throw exception or return false
        return Pair(false, usernames)
    }

    // DashPay Profile methods
    private fun createProfileTransition(
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String? = null,
        avatarHash: ByteArray? = null,
        avatarFingerprint: ByteArray?
    ): DocumentsBatchTransition? {
        checkIdentity()
        val profileDocument = profiles.createProfileDocument(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint, identity!!)
        lastProfileDocument = profileDocument
        val transitionMap = hashMapOf<String, List<Document>?>(
            "create" to listOf(profileDocument)
        )
        //return platform.dpp.document.createStateTransition(transitionMap)
        return null
    }

    fun registerProfile(
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        avatarHash: ByteArray? = null,
        avatarFingerprint: ByteArray?,
        keyParameter: KeyParameter?
    ): Profile {
        val currentProfile = getProfileFromPlatform()
        val signer = WalletSignerCallback(wallet!!, keyParameter)
        val document = if (currentProfile == null) {
            profiles.create(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint, identity!!, 1, signer)
        } else {
            profiles.replace(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint, identity!!, 1, signer)
        }
//        val transition = if (currentProfile == null) {
//            createProfileTransition(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint)
//        } else {
//            replaceProfileTransition(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint)
//        }

//        signStateTransition(transition, keyParameter)
//
//        platform.broadcastStateTransition(transition)
        lastProfileDocument = document
        return Profile(lastProfileDocument!!)
    }

    private fun replaceProfileTransition(
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String? = null,
        avatarHash: ByteArray? = null,
        avatarFingerprint: ByteArray? = null
    ): DocumentsBatchTransition? {
        // first obtain the current document
        val currentProfile = getProfileFromPlatform()

        // change all of the document fields
        val profileData = hashMapOf<String, Any?>()
        profileData.putAll(currentProfile!!.toObject())
        if (displayName != null) {
            profileData["displayName"] = displayName
        } else {
            profileData.remove("displayName")
        }
        if (publicMessage != null) {
            profileData["publicMessage"] = publicMessage
        } else {
            profileData.remove("publicMessage")
        }
        if (avatarUrl != null) {
            profileData["avatarUrl"] = avatarUrl
        } else {
            profileData.remove("avatarUrl")
        }
        if (avatarHash != null) {
            profileData["avatarHash"] = avatarHash
        } else {
            profileData.remove("avatarHash")
        }
        if (avatarFingerprint != null) {
            profileData["avatarFingerprint"] = avatarFingerprint
        } else {
            profileData.remove("avatarFingerprint")
        }
        val profileDocument = platform.dpp.document.createFromObject(profileData)
        // a replace operation must set updatedAt
        profileDocument.updatedAt = Date().time
        lastProfileDocument = platform.dpp.document.createFromObject(profileDocument.toObject()) // copy the document
        lastProfileDocument!!.revision++

        val transitionMap = hashMapOf<String, List<Document>?>(
            "replace" to listOf(profileDocument)
        )
        //return platform.dpp.document.createStateTransition(transitionMap)
        return null
    }

    fun updateProfile(
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        avatarHash: ByteArray? = null,
        avatarFingerprint: ByteArray?,
        keyParameter: KeyParameter?
    ): Profile {
        val signer = WalletSignerCallback(wallet!!, keyParameter)
        lastProfileDocument = profiles.replace(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint, identity!!, 1, signer)
//        val transition = if (currentProfile == null) {
//            createProfileTransition(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint)
//        } else {
//            replaceProfileTransition(displayName, publicMessage, avatarUrl, avatarHash, avatarFingerprint)
//        }

//        signStateTransition(transition, keyParameter)
//
//        platform.broadcastStateTransition(transition)
        return Profile(lastProfileDocument!!)
    }

    fun getProfile(): Document? {
        return profiles.get(uniqueIdString)
    }

    /**
     * Obtains the most recent profile from the network
     */
    fun getProfileFromPlatform(): Document? {
        return profiles.get(uniqueIdString)
    }

    suspend fun watchProfile(
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType
    ): Profile? {
        val updatedAt = if (lastProfileDocument?.updatedAt != null) {
            lastProfileDocument!!.updatedAt!!
        } else {
            -1
        }

        val profileResult = profiles.get(uniqueIdentifier, updatedAt)

        if (profileResult != null) {
            save()
            return Profile(profileResult)
        } else {
            if (retryCount > 0) {
                val nextDelay = delayMillis * when (retryDelayType) {
                    RetryDelayType.SLOW20 -> 5 / 4
                    RetryDelayType.SLOW50 -> 3 / 2
                    else -> 1
                }
                delay(nextDelay)
                return watchProfile(retryCount - 1, nextDelay, retryDelayType)
            }
        }
        return null
    }

    fun watchProfile(
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType,
        callback: UpdateProfileCallback
    ) {
        val updatedAt = if (lastProfileDocument?.updatedAt != null) {
            lastProfileDocument!!.updatedAt!!
        } else {
            -1
        }

        val profileResult = profiles.get(uniqueIdentifier, updatedAt)

        if (profileResult != null) {
            save()
            callback.onComplete(uniqueIdString, profileResult)
        } else {
            if (retryCount > 0) {
                Timer("monitorUpdateProfileStatus", false).schedule(
                    timerTask {
                        val nextDelay = delayMillis * when (retryDelayType) {
                            RetryDelayType.SLOW20 -> 5 / 4
                            RetryDelayType.SLOW50 -> 3 / 2
                            else -> 1
                        }
                        watchProfile(retryCount - 1, nextDelay, retryDelayType, callback)
                    },
                    delayMillis
                )
            } else callback.onTimeout()
        }
    }

    // Contact Requests
    fun addContactToWallet(contactKeyChain: FriendKeyChain, encryptionKey: KeyParameter? = null) {
        when (contactKeyChain.type) {
            FriendKeyChain.KeyChainType.RECEIVING_CHAIN -> {
                wallet!!.run {
                    Preconditions.checkArgument(isEncrypted == (encryptionKey != null))
                    if (isEncrypted) {
                        val encryptedContactKeyChain = contactKeyChain.toEncrypted(keyCrypter, encryptionKey)
                        addReceivingFromFriendKeyChain(encryptedContactKeyChain)
                    } else {
                        addReceivingFromFriendKeyChain(contactKeyChain)
                    }
                }
            }
            FriendKeyChain.KeyChainType.SENDING_CHAIN -> wallet!!.addSendingToFriendKeyChain(contactKeyChain)
        }
    }

    fun maybeDecryptSeed(aesKey: KeyParameter?): DeterministicSeed {
        return wallet!!.run {
            if (isEncrypted) {
                keyChainSeed.decrypt(wallet!!.keyCrypter, "", aesKey)
            } else {
                keyChainSeed
            }
        }
    }

    fun getReceiveFromContactChain(contactIdentity: Identity, aesKey: KeyParameter?): FriendKeyChain {
        val seed = maybeDecryptSeed(aesKey)

        return FriendKeyChain(
            seed,
            null,
            FriendKeyChain.getRootPath(params),
            0,
            uniqueId,
            contactIdentity.id.toSha256Hash()
        )
    }

    fun getReceiveFromContactChain(contactIdentity: Identity, encryptedXpub: ByteArray, aesKey: KeyParameter?): FriendKeyChain {
        val seed = maybeDecryptSeed(aesKey)

        return FriendKeyChain(
            seed,
            null,
            FriendKeyChain.getRootPath(params),
            account,
            uniqueId,
            contactIdentity.id.toSha256Hash()
        )
    }

    fun getSendToContactChain(contactIdentity: Identity, xpub: ByteArray, accountReference: Int): FriendKeyChain {
        val contactIdentityPublicKey = contactIdentity.getPublicKeyById(index)
        return FriendKeyChain(
            params,
            xpub.toHex(),
            EvolutionContact(uniqueId, account, contactIdentity.id.toSha256Hash(), accountReference)
        )
    }

    private fun padAccountLabel(): String {
        return if (accountLabel.length < 16) {
            accountLabel + " ".repeat(16 - accountLabel.length)
        } else {
            accountLabel
        }
    }

    fun encryptExtendedPublicKey(
        xpub: ByteArray,
        contactIdentity: Identity,
        index: Int,
        aesKey: KeyParameter?
    ): Pair<ByteArray, ByteArray> {
        val contactIdentityPublicKey = contactIdentity.getPublicKeyById(index)
            ?: throw IllegalArgumentException("index $index does not exist for $contactIdentity")

        val contactPublicKey = contactIdentityPublicKey.getKey()

        return encryptExtendedPublicKey(xpub, contactPublicKey, contactIdentityPublicKey.type, aesKey)
    }

    /**
     *
     * @param xpub ByteArray The serialized extended public key obtained from [DeterministicKeyChain.getWatchingKey().serialize]
     * @param contactPublicKey ECKey The public key of the identity
     * @param signingAlgorithm KeyType
     * @param aesKey KeyParameter? The decryption key to the encrypted wallet
     * @return ByteArray The encrypted extended public key
     */
    fun encryptExtendedPublicKey(
        xpub: ByteArray,
        contactPublicKey: ECKey,
        signingAlgorithm: KeyType,
        aesKey: KeyParameter?
    ): Pair<ByteArray, ByteArray> {
        val keyCrypter = KeyCrypterECDH()
        checkIdentity()
        // first decrypt our identity key if necessary (currently uses the first key [0])
        val decryptedIdentityKey = maybeDecryptKey(KeyIndexPurpose.AUTHENTICATION.ordinal, signingAlgorithm, aesKey)

        // derived the shared key (our private key + their public key)
        val encryptionKey = keyCrypter.deriveKey(decryptedIdentityKey, contactPublicKey)

        // encrypt
        val encryptedData = keyCrypter.encrypt(xpub, encryptionKey)

        // format as a single byte array
        val boas = ByteArrayOutputStream(encryptedData.initialisationVector.size + encryptedData.encryptedBytes.size)
        boas.write(encryptedData.initialisationVector)
        boas.write(encryptedData.encryptedBytes)

        // encrypt
        val encryptedAccountLabel = keyCrypter.encrypt(padAccountLabel().toByteArray(), encryptionKey)

        // format as a single byte array
        val accountLabelBoas =
            ByteArrayOutputStream(encryptedAccountLabel.initialisationVector.size + encryptedAccountLabel.encryptedBytes.size)
        accountLabelBoas.write(encryptedAccountLabel.initialisationVector)
        accountLabelBoas.write(encryptedAccountLabel.encryptedBytes)

        return Pair(boas.toByteArray(), accountLabelBoas.toByteArray())
    }

    fun decryptExtendedPublicKey(
        encryptedXpub: ByteArray,
        contactIdentity: Identity,
        contactKeyIndex: Int,
        keyIndex: Int,
        aesKey: KeyParameter?
    ): String {
        val contactIdentityPublicKey = contactIdentity.getPublicKeyById(contactKeyIndex)
            ?: throw IllegalArgumentException("index $contactKeyIndex does not exist for $contactIdentity")
        val contactPublicKey = contactIdentityPublicKey.getKey()

        return decryptExtendedPublicKey(
            encryptedXpub,
            contactPublicKey,
            contactIdentityPublicKey.type,
            keyIndex,
            aesKey
        )
    }

    /**
     *
     * @param encryptedXpub ByteArray
     * @param contactPublicKey ECKey
     * @param signingAlgorithm KeyType
     * @param keyParameter KeyParameter The decryption key to the encrypted wallet
     * @return DeterministicKey The extended public key without the derivation path
     */
    fun decryptExtendedPublicKey(
        encryptedXpub: ByteArray,
        contactPublicKey: ECKey,
        signingAlgorithm: KeyType,
        keyIndex: Int,
        keyParameter: KeyParameter?
    ): String {
        val keyCrypter = KeyCrypterECDH()

        // first decrypt our identity key if necessary (currently uses the first key [0])
        val decryptedIdentityKey =
            maybeDecryptKey(keyIndex, signingAlgorithm, keyParameter)

        // derive the shared key (our private key + their public key)
        val encryptionKey = keyCrypter.deriveKey(decryptedIdentityKey, contactPublicKey)

        // separate the encrypted data (IV + ciphertext) and then decrypt the extended public key
        val encryptedData =
            EncryptedData(encryptedXpub.copyOfRange(0, 16), encryptedXpub.copyOfRange(16, encryptedXpub.size))
        val decryptedData = keyCrypter.decrypt(encryptedData, encryptionKey)

        return DeterministicKey.deserializeContactPub(params, decryptedData).serializePubB58(params)
    }

    fun addContactPaymentKeyChain(contactIdentity: Identity, contactRequest: Document, encryptionKey: KeyParameter?) {
        val accountReference = if (contactRequest.data.containsKey("accountReference")) {
            (contactRequest.data["accountReference"] as Long).toInt()
        } else {
            0 // default account reference
        }

        val contact = EvolutionContact(uniqueId, account, contactIdentity.id.toSha256Hash(), accountReference)

        if (!wallet!!.hasSendingKeyChain(contact)) {
            val xpub = decryptExtendedPublicKey(
                contactRequest.data["encryptedPublicKey"] as ByteArray,
                contactIdentity,
                (contactRequest.data["senderKeyIndex"] as Long).toInt(),
                (contactRequest.data["recipientKeyIndex"] as Long).toInt(),
                encryptionKey
            )
            val contactKeyChain = FriendKeyChain(wallet!!.params, xpub, contact)
            addContactToWallet(contactKeyChain)
        }
    }

    fun addPaymentKeyChainFromContact(
        contactIdentity: Identity,
        contactRequest: ContactRequest,
        encryptionKey: KeyParameter?
    ): Boolean {
        val contact = EvolutionContact(uniqueId, account, contactIdentity.id.toSha256Hash(), -1)
        if (!wallet!!.hasReceivingKeyChain(contact)) {
            val encryptedXpub = Converters.byteArrayFromBase64orByteArray(contactRequest.encryptedPublicKey)
            val senderKeyIndex = contactRequest.senderKeyIndex
            val recipientKeyIndex = contactRequest.recipientKeyIndex
            val contactKeyChain = getReceiveFromContactChain(contactIdentity, encryptionKey)

            val serializedContactXpub = decryptExtendedPublicKey(encryptedXpub, contactIdentity, recipientKeyIndex, senderKeyIndex, encryptionKey)

            val ourContactXpub = contactKeyChain.watchingKey.serializeContactPub()
            val ourSerializedXpub = DeterministicKey.deserializeContactPub(params, ourContactXpub).serializePubB58(params)

            // check that this contactRequest is for the default account
            if (serializedContactXpub.contentEquals(ourSerializedXpub)) {
                addContactToWallet(contactKeyChain, encryptionKey)
                return true
            } else {
                log.warn("contactRequest does not match account 0")
            }
        }
        return false
    }

    fun getContactNextPaymentAddress(contactId: Identifier, accountReference: Int): Address {
        return wallet!!.currentAddress(
            EvolutionContact(uniqueIdString, account, contactId.toString(), accountReference),
            FriendKeyChain.KeyChainType.SENDING_CHAIN
        )
    }

    fun getNextPaymentAddressFromContact(contactId: Identifier): Address {
        return wallet!!.currentAddress(
            EvolutionContact(uniqueIdString, account, contactId.toString(), -1),
            FriendKeyChain.KeyChainType.RECEIVING_CHAIN
        )
    }

    fun getContactTransactions(identityId: Identifier, accountReference: Int): List<Transaction> {
        val contact = EvolutionContact(uniqueIdString, account, identityId.toString(), accountReference)
        return wallet!!.getTransactionsWithFriend(contact)
    }

    fun getContactForTransaction(tx: Transaction): String? {
        val contact = wallet!!.getFriendFromTransaction(tx) ?: return null
        return if (uniqueId == contact.evolutionUserId) {
            contact.friendUserId.toStringBase58()
        } else {
            contact.evolutionUserId.toStringBase58()
        }
    }

    fun getAccountReference(encryptionKey: KeyParameter?, fromIdentity: Identity): Int {
        val privateKey = maybeDecryptKey(0, KeyType.ECDSA_SECP256K1, encryptionKey)

        val receiveChain = getReceiveFromContactChain(fromIdentity, encryptionKey)

        val extendedPublicKey = receiveChain.watchingKey.dropPrivateBytes()

        val accountSecretKey = HDUtils.hmacSha256(privateKey!!.privKeyBytes, extendedPublicKey.serializeContactPub())

        val accountSecretKey28 = Sha256Hash.wrapReversed(accountSecretKey).toBigInteger().toInt() ushr 4

        val shortenedAccountBits = account and 0x0FFFFFFF

        val version = 0

        val versionBits: Int = (version shl 28)

        return versionBits or (accountSecretKey28 xor shortenedAccountBits)
    }

    fun getInvitationHistory(): Map<Identifier, Identity?> {
        val inviteTxs = authenticationGroup!!.invitationFundingTransactions
        val listIds = inviteTxs.map { Identifier.from(it.identityId) }

        return listIds.associateBy({ it }, { platform.identities.get(it) })
    }

    fun getInvitationString(cftx: AssetLockTransaction, encryptionKey: KeyParameter?): String {
        val txid = cftx.txId

        val privateKey = maybeDecryptKey(cftx.assetLockPublicKey, encryptionKey)
        val wif = privateKey?.getPrivateKeyEncoded(wallet!!.params)
        return "assetlocktx=$txid&pk=$wif&du=${currentUsername!!}&islock=${cftx.confidence.instantSendlock.toStringHex()}"
    }

    // Transaction Metadata Methods
    @Throws(KeyCrypterException::class)
    fun publishTxMetaData(txMetadataItems: List<TxMetadataItem>, keyParameter: KeyParameter?) {
        val keyIndex = 1
        val encryptionKeyIndex = 0
        val encryptionKey = privateKeyAtPath(keyIndex, TxMetadataDocument.childNumber, encryptionKeyIndex, KeyType.ECDSA_SECP256K1, keyParameter)

        var lastItem: TxMetadataItem? = null
        var currentIndex = 0
        log.info("publish ${txMetadataItems.size} by breaking it up into pieces")
        while (currentIndex < txMetadataItems.size) {
            var estimatedDocSize = 0
            val currentMetadataItems = arrayListOf<TxMetadataItem>()

            log.info("publish: determine how items can go in the next txmetadata document: $currentIndex")
            while (currentIndex < txMetadataItems.size) {
                lastItem = txMetadataItems[currentIndex]
                val estimatedItemSize = lastItem.getSize() + 4
                if ((estimatedDocSize + estimatedItemSize) < TxMetadataDocument.MAX_ENCRYPTED_SIZE) {
                    estimatedDocSize += estimatedItemSize
                    currentMetadataItems.add(lastItem)
                    currentIndex++
                    log.info("publish: we can add another document for a total of ${currentMetadataItems.size}")
                } else {
                    break // leave this loop
                }
            }

            log.info("publishing ${currentMetadataItems.size} items of ${txMetadataItems.size}")
            val metadataBytes = Cbor.encode(currentMetadataItems.map { it.toObject() })

            // encrypt data
            val cipher = KeyCrypterAESCBC()
            val aesKey = cipher.deriveKey(encryptionKey)
            val encryptedData = cipher.encrypt(metadataBytes, aesKey)

            TxMetadata(platform).create(
                keyIndex,
                encryptionKeyIndex,
                encryptedData.initialisationVector.plus(encryptedData.encryptedBytes),
                identity!!,
                KeyIndexPurpose.AUTHENTICATION.ordinal,
                WalletSignerCallback(wallet!!, keyParameter)
            )
            currentMetadataItems.clear()
        }
    }

    fun getTxMetaData(createdAfter: Long = -1, keyParameter: KeyParameter?): Map<TxMetadataDocument, List<TxMetadataItem>> {
        val documents = TxMetadata(platform).get(uniqueIdentifier, createdAfter)

        val results = LinkedHashMap<TxMetadataDocument, List<TxMetadataItem>>()
        documents.forEach {
            val doc = TxMetadataDocument(it)
            results[doc] = decryptTxMetadata(TxMetadataDocument(it), keyParameter)
        }
        return results
    }

    @Throws(KeyCrypterException::class)
    fun decryptTxMetadata(txMetadataDocument: TxMetadataDocument, keyParameter: KeyParameter?): List<TxMetadataItem> {
        val cipher = KeyCrypterAESCBC()
        val encryptionKey = privateKeyAtPath(
            txMetadataDocument.keyIndex,
            TxMetadataDocument.childNumber,
            txMetadataDocument.encryptionKeyIndex,
            KeyType.ECDSA_SECP256K1,
            keyParameter
        )
        val aesKeyParameter = cipher.deriveKey(encryptionKey)

        // now decrypt
        val encryptedData = EncryptedData(
            txMetadataDocument.encryptedMetadata.copyOfRange(0, 16),
            txMetadataDocument.encryptedMetadata.copyOfRange(16, txMetadataDocument.encryptedMetadata.size)
        )

        val decryptedData = cipher.decrypt(encryptedData, aesKeyParameter)
        val decryptedList = Cbor.decodeList(decryptedData)

        return decryptedList.map { TxMetadataItem(it as Map<String, Any?>) }
    }

    fun deleteDocument(typeLocator: String, documentId: Identifier, keyParameter: KeyParameter?): Boolean {
        val documentsToDelete = platform.documents.get(typeLocator, DocumentQuery.builder().where("\$id", "==", documentId).build())
        return if (documentsToDelete.isNotEmpty()) {
//            val transition = platform.dpp.document.createStateTransition(
//                mapOf(
//                    "delete" to listOf(
//                        documentsToDelete.first()
//                    )
//                )
//            )
//            signStateTransition(transition, keyParameter)
//            platform.client.broadcastStateTransition(transition)
            true
        } else {
            false
        }
    }
}
