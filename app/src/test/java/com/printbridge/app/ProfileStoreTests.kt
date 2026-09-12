package com.printbridge.app

import android.content.Context
import com.printbridge.core.DefaultProfiles
import com.printbridge.core.TransportType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProfileStoreTests {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("printbridge_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsAreRestoredWhenNothingWasSaved() {
        assertEquals(DefaultProfiles.all, ProfileStore(context).load())
    }

    @Test
    fun createEditDuplicateDeleteAndRestartRoundTrip() {
        val store = ProfileStore(context)
        val original = DefaultProfiles.all.first()
        val created = original.copy(id = UUID.randomUUID().toString(), displayName = "Тестовый профиль", verified = false)
        store.save(store.load() + created)

        val afterCreate = ProfileStore(context).load()
        assertNotNull(afterCreate.find { it.id == created.id })

        val edited = created.copy(displayName = "Измененный профиль", chunkSize = 37)
        store.save(afterCreate.map { if (it.id == created.id) edited else it })
        val afterEdit = ProfileStore(context).load()
        assertEquals("Измененный профиль", afterEdit.single { it.id == created.id }.displayName)
        assertEquals(37, afterEdit.single { it.id == created.id }.chunkSize)

        val duplicate = edited.copy(id = UUID.randomUUID().toString(), displayName = "Копия")
        store.save(afterEdit + duplicate)
        val afterDuplicate = ProfileStore(context).load()
        assertTrue(afterDuplicate.any { it.id == created.id })
        assertTrue(afterDuplicate.any { it.id == duplicate.id })

        store.save(afterDuplicate.filterNot { it.id == created.id })
        val afterDeleteAndRestart = ProfileStore(context).load()
        assertFalse(afterDeleteAndRestart.any { it.id == created.id })
        assertTrue(afterDeleteAndRestart.any { it.id == duplicate.id })
    }

    @Test
    fun profileFieldsSurvivePersistenceRoundTrip() {
        val profile = DefaultProfiles.all.first().copy(
            displayName = "Полный профиль",
            manufacturer = "Vendor",
            model = "Model 1",
            alternativeNames = listOf("Alias"),
            bluetoothNamePatterns = listOf("MTP"),
            bluetoothDeviceName = "MTP-2",
            bluetoothDeviceAddress = "AA:BB:CC:DD:EE:FF",
            networkHost = "printer.local",
            networkPort = 9100,
            networkConnectTimeoutMs = 1234,
            networkWriteTimeoutMs = 2345,
            chunkSize = 73,
            delayBetweenChunksMs = 9,
            writeTimeoutMs = 4321,
            usbVid = 0x0483,
            usbPid = 0x5743,
            knownQuirks = listOf("RFCOMM channel 1 fallback"),
            verified = false,
            notes = "note"
        )
        ProfileStore(context).save(listOf(profile))
        val restored = ProfileStore(context).load().single { it.id == profile.id }
        assertEquals(profile, restored)
    }

    @Test
    fun savedRuntimeFieldsSurviveDefaultsMerge() {
        val defaultId = DefaultProfiles.all.first().id
        val edited = DefaultProfiles.all.first().copy(
            transportType = TransportType.USB,
            usbVid = 0x1234,
            usbPid = 0x5678,
            writeTimeoutMs = 9000,
            networkHost = "10.0.0.9"
        )

        ProfileStore(context).save(listOf(edited))
        val restored = ProfileStore(context).load().single { it.id == defaultId }

        assertTrue(restored.transportType == TransportType.USB)
        assertEquals(0x1234, restored.usbVid)
        assertEquals(0x5678, restored.usbPid)
        assertEquals(9000L, restored.writeTimeoutMs)
        assertEquals("10.0.0.9", restored.networkHost)
    }

    @Test
    fun malformedStoredJsonFallsBackToDefaultsInsteadOfCrashing() {
        context.getSharedPreferences("printbridge_profiles", Context.MODE_PRIVATE)
            .edit()
            .putString("profiles", "{ not json ]")
            .commit()

        assertEquals(DefaultProfiles.all, ProfileStore(context).load())
    }
}
