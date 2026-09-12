# PrintBridge release (R8) rules.
#
# The app has no serialization framework and no JNI entry points, so the default
# proguard-android-optimize.txt rules plus AGP-supplied Compose rules cover almost
# everything. The rules below only protect the reflection and platform corners that R8
# cannot see.

# Keep line numbers so release crash reports stay actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# BluetoothSppPrinterTransport.createCompatRfcommSocket() invokes this method
# reflectively on android.bluetooth.BluetoothDevice (GOOJPRT channel-1 fallback).
# Platform classes are not processed by R8, but keeping the rule documents the
# dependency and protects against future repackaging.
-keepclassmembers class android.bluetooth.BluetoothDevice {
    public android.bluetooth.BluetoothSocket createRfcommSocket(int);
}

# Domain errors are surfaced to the user through message text and are matched by tests.
-keep class com.printbridge.core.PrintBridgeError { *; }
-keep class com.printbridge.core.PrintBridgeError$* { *; }

# Enum entries are looked up by name when profiles are read from SharedPreferences
# (PrinterProtocol.valueOf / TransportType.valueOf).
-keepclassmembers enum com.printbridge.core.PrinterProtocol {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.printbridge.core.TransportType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.printbridge.app.PrintJobTemplate {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
