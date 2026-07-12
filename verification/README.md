# Verification

This directory contains executable platform contract suites. Each verifier is an independently testable Maven module and may own an image under its local `image/` directory.

Verifiers test stable platform contracts rather than implementation increments. They must not contain administrator credentials, environment inventory, or third-party service deployment configuration.

Current modules:

- `storage-contract` - Ceph RGW bucket and object-operation contract
