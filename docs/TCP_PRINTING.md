# TCP Printing

`TcpPrinterTransport` sends raw bytes to a host and port and is the `Network` transport in the app.

- The host and port come from the profile (`networkHost`, `networkPort`); the default RAW printing
  port is `9100`.
- Connection is non-blocking with a bounded `finishConnect()` loop and up to three attempts, so
  `networkConnectTimeoutMs` really bounds the whole connect phase. An unreachable printer surfaces
  as `NetworkPrinterUnreachable`.
- Writes are non-blocking (`SocketChannel` + `Selector`) and bounded by `networkWriteTimeoutMs`, so
  a printer that stops reading cannot block the job forever. A timeout surfaces as
  `WriteFailed` with the byte offset, and a closed socket as `ConnectionLost`.
- After a failure the transport keeps its `ERROR` state even once `disconnect()` is called.

Both timeouts are validated in the profile editor (100..120000 ms).

The same ESC/POS, TSPL or GOOJPRT bytes can be sent through the fake, TCP, Bluetooth and USB
transports; `TransportTests` and `TransportAuditTests` assert byte-exact delivery over a loopback
socket for payloads up to 128 KiB.

## Finding printers

`NetworkPrinterDiscovery` probes ports `9191` (PrintBridge Windows bridge), `9100` (RAW/JetDirect),
`631` (IPP) and `515` (LPR) on the local /24. It reports a candidate for every open port, which is
a hint rather than a printer identity, and it caps the number of concurrently open sockets at
`DEFAULT_MAX_CONCURRENT_PROBES` (48). Use `ПРОФИЛИ` to set the host manually when discovery is not
appropriate for the network.
