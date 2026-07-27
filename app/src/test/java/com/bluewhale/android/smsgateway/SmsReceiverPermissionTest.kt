package com.bluewhale.android.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The SMS receiver has to stay exported for the system to deliver to it, so the only
 * thing keeping other apps out is the permission the sender must hold. Without it any
 * app on the device can post a crafted SMS_RECEIVED intent straight to this component
 * and have forged messages forwarded to the user's webhook.
 */
class SmsReceiverPermissionTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest(): File =
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")

    private fun receiverNamed(suffix: String): Element {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest())

        val receivers = doc.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val element = receivers.item(i) as Element
            if (element.getAttributeNS(androidNs, "name").endsWith(suffix)) return element
        }
        error("receiver $suffix not declared in the manifest")
    }

    @Test
    fun `sms receiver may only be triggered by the system`() {
        val receiver = receiverNamed("SmsBroadcastReceiver")

        assertEquals(
            "SmsBroadcastReceiver must require BROADCAST_SMS of its senders",
            "android.permission.BROADCAST_SMS",
            receiver.getAttributeNS(androidNs, "permission")
        )
    }

    @Test
    fun `every exported receiver states who may trigger it`() {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest())

        val receivers = doc.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val element = receivers.item(i) as Element
            if (element.getAttributeNS(androidNs, "exported") != "true") continue
            val name = element.getAttributeNS(androidNs, "name")
            assertTrue(
                "exported receiver $name has no android:permission, so any app can trigger it",
                element.getAttributeNS(androidNs, "permission").isNotBlank()
            )
        }
    }
}
