# Controlled native admission staging

`NativeProviderTransport.openControlled(...)` is an explicit opt-in. It constructs a disabled player admission gate before binding the UDP listener. Existing `open(...)` and public `NativeAdmissionServerChannel` constructors retain their legacy serving behavior. Connectivity observations and separately configured signed diagnostics do not enable the player gate.

The caller serializes application updates and owns their durable record and live control authority. The transport supplies this narrow sequence:

1. `beginAdmissionUpdate()` synchronously disables new player admissions and creates an opaque, single-use token for this live native instance. Previously admitted peers stay alive. Every new begin invalidates the preceding token.
2. If keys change, await `installTicketKeys(token, ownedKeys)` once. It installs an atomic snapshot while admission remains disabled. One token cannot install a second snapshot; a failed or competing installation invalidates that token. A token may retain the existing snapshot for a state-only transition.
3. Finish the caller's durable application write. A saved basis is an input to reapplication after restart, never proof that this native instance is ready. The caller must not invoke commit following a failed save.
4. Call `commitAdmissionUpdate(token, requireCurrent)`. The synchronous, nonblocking guard checks the original authority deadline, writer/key, desired revision, applied policy/profile and durable application ownership. It must throw if any condition is stale. It runs outside transport/native locks; immediately afterward, without asynchronous work, the transport rechecks the same live instance/token, fixed installed-key eligibility, permanent drain and close state before enabling. A guard exception invalidates its still-current token. The guard must not block or transfer completion to another thread.

The token does not authenticate a caller, carry a grant, renew an authority deadline, prove persistence, or imply control `READY`. Those remain caller obligations. Snapshot installation is frozen when commit starts, so a concurrent install cannot change the content under an earlier durable write. A guard may synchronously reenter and replace/drain/close the transport; the original token then cannot enable it. A stale token cannot invalidate a newer one.

Controlled `applyState("serving")` only observes an already enabled gate and cannot bypass commit. Controlled `applyState("draining")` disables admissions reversibly and invalidates outstanding tokens. Explicit `drain()` is permanent for the native instance; `close()` disables immediately and completes after native termination. Calling the legacy key-install method on a controlled listener disables and invalidates the current update; it never enables admission.

Staging rejects new player reservations before ticket validation and native peer allocation. Pending reservations from before staging are cancelled and cannot become admitted children; capacity remains reserved until their actual native cleanup. Native work already in progress before the staging boundary may allocate and then tear down. Staging is not a kernel packet barrier, and the implementation does not claim to undo already-started native allocation. Admitted peers continue their existing transport and login lifetime rules.

## Evidence

`AdmissionGateTest` covers initial disable, token identity/single use, pending cancellation without early capacity release, admitted-peer preservation, permanent drain and close. `NativeAdmissionStagingTest` uses actual IPv4 and IPv6 sockets and valid player first-STUN packets with matching integrity. It asserts zero new native construction while staged, matching first-response transaction IDs after explicit commit, failed-save/guard behavior, stale/foreign tokens, key invalidation, concurrent replacement, expired keys and data-channel continuity for existing peers. These are local native transport tests, not Minecraft account login or gameplay proof.

Run with the pinned JNI build containing native construction diagnostics:

```sh
./gradlew -I /path/to/pinned-native.init.gradle \
  :external-signaling:nativeAdmissionTest --tests '*NativeAdmissionStagingTest' --tests '*NativeAdmissionIntegrationTest' \
  :external-signaling:test --tests '*AdmissionGateTest' -x :external-signaling:jacocoTestReport
```

ProviderClient durable application integration, signed source basis publication, full readiness synchronization and deployment remain separate work.
