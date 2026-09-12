# Bluetooth SPP

Bluetooth Classic SPP is implemented in `printer-bluetooth` (`BluetoothSppPrinterTransport`) and is
selectable in the app as the `Bluetooth` transport.

## Connection strategy

`connect()` walks a compatibility ladder until one attempt succeeds:

1. Standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`, secure socket
2. same UUID, insecure socket
3. PrintBridge virtual-printer UUID `7F4D0D2E-4FB8-4E8F-94F2-86E3ADFCE86F`, secure then insecure
4. GOOJPRT/MTP fallback: reflective `createRfcommSocket(1)` on channel 1

Each attempt is bounded by `connectTimeoutMs` (default 10 s) and the failed socket is closed before
the next one is tried. Failure is reported as `BluetoothUnavailable`,
`BluetoothPermissionDenied`, `BluetoothDeviceNotFound` or `BluetoothConnectionFailed`.

## Write timeout

`write()` writes and flushes the chunk under `writeTimeoutMs` (default 10 s) using the shared
`withIoDeadline` helper. A blocking RFCOMM write does not react to thread interruption, so on
timeout the socket is closed to release the worker thread and the job fails with `WriteFailed`.
Without this bound a stalled printer used to block the whole print queue, because `PrintQueue`
holds its mutex for the duration of a job.

Both timeouts come from the profile (`writeTimeoutMs`) and can be edited in the `ПРОФИЛИ` tab.

## Permissions

- Android 12+ (`API 31+`): `BLUETOOTH_CONNECT` (and `BLUETOOTH_SCAN`, declared with
  `neverForLocation`, required by `BluetoothAdapter.cancelDiscovery()`). Requested at runtime via
  `RequestMultiplePermissions`.
- Android 11 and below: legacy `BLUETOOTH` / `BLUETOOTH_ADMIN`, granted at install time.

The app lists **bonded** devices only; it never starts a scan.

## Virtual printer server

`BluetoothVirtualPrinterServer` listens on the SPP UUID and feeds everything a client sends into
`simulator-core`, so one Android device can act as a virtual printer for another. Reads are capped
at `MAX_CLIENT_BYTES` (4 MiB) and `stop()` closes the listening socket even while `accept()` is
pending.

## Untested

No physical Bluetooth printer has been used with this code. Real-world behaviour of the RFCOMM
fallback ladder, of the write timeout recovery, and of the vendor-specific channel-1 quirk is
unverified; see `docs/KNOWN_LIMITATIONS.md`.
