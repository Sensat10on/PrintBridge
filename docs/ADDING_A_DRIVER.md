# Adding A Driver

Add a driver in `printer-drivers` that implements `PrinterDriver`. The driver must transform logical printer operations into bytes and must not open sockets, discover devices, request Android permissions, or depend on UI. Add independent golden fixtures and simulator parser support where practical.
