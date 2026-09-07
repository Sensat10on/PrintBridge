# Testing Without A Printer

## Level 1

Unit tests run protocol generation, profile, raster, queue, fake transport, chunking, fault injection, and simulator parser checks. This proves deterministic software behavior only.

## Level 2

PrintBridge writes to `FakePrinterTransport` and the inspector displays writes, bytes, chunks, HEX, RAW, timing-adjacent capture data, and errors. This proves queue and transport boundaries without hardware.

## Level 3

Android or JVM code sends RAW TCP bytes to `127.0.0.1:9100`, where PrintBridge Simulator receives, detects, parses, and previews them. This proves TCP byte preservation and simulator compatibility.

## Level 4

Future Milestone 2: physical Android Phone A sends Bluetooth SPP to physical Android Phone B running simulator server mode. This remains UNTESTED until implemented and real devices are available.
