# SodaMesh — offline soft-drink ordering over BLE mesh (bitchat method)

Single Android app, 2 flavors, Compose, no web.

- `customer` flavor: menu + cart + send ORDER over mesh
- `vendor` flavor: advertise SODA-STORE + alert + accept/reject
- `core-mesh`: bitchat-protocol port (RelayController TTL7, Noise, courier/outbox)

## Modules (10 parallel workstreams)
1. scaffold/gradle/flavors/Hilt — Agent 1
2. models + order protocol — Agent 2
3. BLE advertise/scan — Agent 3
4. GATT transport + fragmentation — Agent 4
5. mesh router + RelayController — Agent 5
6. crypto + courier/outbox — Agent 6
7. customer UI — Agent 7
8. vendor UI — Agent 8
9. persistence (Room/DataStore) + repos — Agent 9
10. permissions + ForegroundService + tests — Agent 10

Build: `./gradlew :app:assembleCustomerDebug :app:assembleVendorDebug`
Requires 2 physical phones for BLE (emulator cannot do real BLE).
