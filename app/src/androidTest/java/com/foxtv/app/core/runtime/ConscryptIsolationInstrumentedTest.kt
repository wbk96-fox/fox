package com.foxtv.app.core.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.conscrypt.Conscrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security

@RunWith(AndroidJUnit4::class)
class ConscryptIsolationInstrumentedTest {
    @Test
    fun scopedConscryptDoesNotMutateProcessProvidersAndOwnsItsEngine() {
        val before = Security.getProviders()

        val material = CloudstreamTlsClientFactory.createTlsMaterial()
        val engine = material.sslContext.createSSLEngine()

        val after = Security.getProviders()
        assertArrayEquals(before.map { it.name }.toTypedArray(), after.map { it.name }.toTypedArray())
        assertTrue(before.indices.all { before[it] === after[it] })
        assertFalse(after.any { it === material.provider })
        assertTrue(Conscrypt.isConscrypt(engine))
    }
}
