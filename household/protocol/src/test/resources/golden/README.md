# Golden wire samples

Committed JSON for protocol messages. A test fails when the encoder's output drifts from these files, so a
wire change is always a visible, reviewed diff. Regenerate by deleting a file, running the tests, and copying
the `GOLDEN ... BEGIN/END` block from the test report's standard output.
