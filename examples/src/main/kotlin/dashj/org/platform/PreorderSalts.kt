/**
 * Copyright (c) 2020-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package dashj.org.platform

import org.dashj.platform.dapiclient.model.DocumentQuery
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.util.ByteArray32
import org.dashj.platform.sdk.Client
import org.dashj.platform.sdk.client.ClientOptions
import org.dashj.platform.sdk.platform.Documents

class PreorderSalts {
    companion object {
        lateinit var sdk: Client

        @JvmStatic
        fun main(args: Array<String>) {
            sdk = Client(ClientOptions(network = args[0]))
            sdk.platform.useValidNodes()
            getDocuments()
        }

        private fun getDocuments() {
            val platform = sdk.platform

            var startAt = 0
            var documents: List<Document>? = null
            var requests = 0
            var queryOpts = DocumentQuery.Builder().build()

            do {
                try {
                    documents = platform.documents.get("dpns.preorder", queryOpts)

                    requests += 1

                    for (doc in documents) {
                        println(
                            "Salted domain hash: " + (doc.data["saltedDomainHash"] as ByteArray32).toBase64() +
                                " Identity: " + doc.ownerId + " id: " + doc.id
                        )
                    }

                    startAt += Documents.DOCUMENT_LIMIT
                    if (documents.isNotEmpty()) {
                        queryOpts = DocumentQuery.Builder().startAfter(documents.last().id).build()
                    }
                } catch (e: Exception) {
                    println("\nError retrieving results (startAt =  $startAt)")
                    println(e.message)
                }
            } while (requests == 0 || documents!!.size >= 100)
        }
    }
}
