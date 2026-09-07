# Adding A Transport

Add a transport in `printer-transport` that implements `PrinterTransport`. A transport may connect, disconnect, report state, and write bytes. It must not generate ESC/POS, TSPL, or other protocol commands.
