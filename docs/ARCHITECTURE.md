# Architecture

Milestone 1 keeps strict boundaries:

```text
Document -> Print Core -> Printer Driver -> Printer Transport -> Printer
```

`print-core` owns domain models, job state, queue serialization, chunking, profile metadata, errors, and raster primitives. `printer-drivers` owns deterministic ESC/POS and TSPL byte generation. `printer-transport` owns fake and TCP I/O only. `simulator-core` detects and parses raw byte streams and creates virtual preview models. `app` is a thin Compose UI over these modules.
