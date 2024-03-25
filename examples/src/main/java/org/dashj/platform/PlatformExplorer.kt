package org.dashj.platform

import org.bitcoinj.core.Base58
import org.dashj.platform.sdk.*
import java.util.Scanner

object PlatformExplorer {

    init {
        System.loadLibrary("sdklib")
    };


    @JvmStatic
    fun main(args: Array<String>) {
        val identifier = example.getDocument()
        println(Base58.encode(identifier._0._0))

        println("Platform Explorer")
        println("-----------------")
        println("Main Menu")
        println("1. Identities")

        println("Enter an identity id:")
        val scanner = Scanner(System.`in`)
        val idString = scanner.nextLine()

        println(" > $idString")

        val value = example.fetchIdentity3(Identifier(Base58.decode(idString)));
        if (value.ok != null) {
            when (value.ok.tag) {
                Identity.Tag.V0 -> {
                    println("id: ${value.ok.v0._0.id}")
                    println("balance: ${value.ok.v0._0.balance}")
                    println("keys: ${value.ok.v0._0.publicKeyCount}")
                }
                else -> {

                }
            }
        } else {
            println("fetch identity error: ${value.error}")
        }

    }
}

// MTMoBVf6N9zpPwCTQ51vfttFYmHwfacWUHFupFTApUG