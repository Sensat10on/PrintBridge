package com.printbridge.bluetooth

import android.Manifest
import android.os.Build
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class BluetoothPermissionTests {
    @Test fun android12AndLaterRequiresConnectAndScanAtRuntime() {
        assertContentEquals(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
            BluetoothPermissions.runtimePermissions(Build.VERSION_CODES.S)
        )
    }

    @Test fun android11AndEarlierDoesNotRequestRuntimeBluetoothPermissions() {
        assertTrue(BluetoothPermissions.runtimePermissions(Build.VERSION_CODES.R).isEmpty())
    }
}
