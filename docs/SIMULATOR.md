# Simulator

PrintBridge Simulator is a first-class virtual printer. It conservatively detects `ESC_POS`, `TSPL`, or `UNKNOWN`, records evidence, parses the generated Milestone 1 command subsets, and creates virtual receipt or label preview models.

The TCP simulator binds to `127.0.0.1:9100` by default. Use `0.0.0.0` only when LAN testing is intentional.
