# Canonical origins for the optional control transport

The optional control profile uses this explicit ASCII grammar for every signed
audience and trusted expected origin. Validation accepts the original string or
rejects it. It never normalizes a received or signed value, resolves DNS, or
changes the core `ProviderCrypto.origin` / `providerOrigin` helpers.

An origin is lowercase `https://HOST` with an optional `:PORT`. Development HTTP
is allowed only for exactly `localhost`, `127.0.0.1` or `[::1]`. Its form is also
lowercase `http://HOST` with an optional port. The entire origin is at most 2048
ASCII characters. Path, even `/`, query, fragment, userinfo, whitespace, control
characters, percent escapes and backslashes are prohibited.

An explicit port is decimal 1–65535 without leading zeros or a sign. The default
port must be omitted: 443 for HTTPS and 80 for HTTP. Port 0, `-0`, an empty port,
overflow, hexadecimal/exponent notation and default-port aliases are rejected.

HOST is exactly one of:

- Lowercase ASCII DNS labels of 1–63 characters, separated by dots, with at most
  253 characters total. A label starts and ends with `[a-z0-9]` and contains only
  `[a-z0-9-]`. The final label starts with `[a-z]`. Empty labels and trailing dots
  are rejected. Single-label names are allowed. Every label starting `xn--` is
  excluded from this profile.
- Canonical IPv4: exactly four decimal octets, each 0–255, without leading zeros.
  Integer, shortened, octal and hexadecimal aliases are rejected.
- Bracketed IPv6: eight 16-bit pieces represented with shortest lowercase hex,
  compressing the longest consecutive run of at least two zero pieces with `::`.
  The first run wins a tie. A single zero is never compressed. IPv4-mapped
  addresses use their hexadecimal form, such as `[::ffff:c000:280]`; dotted tails,
  uppercase/expanded aliases, unbracketed literals and zone identifiers are
  rejected. Only the exact literal `[::1]` qualifies for development HTTP.

Raw Unicode and all `xn--` IDN labels, as well as trailing-dot provider origins,
**cannot advertise this optional control profile**. They can use the existing
core transport. Supporting them requires a future agreed normalization profile;
this restriction must not silently fall back to different origin rules inside
an opted-in control generation. No cryptographic canonicalization step is added.

The narrower DNS rules avoid platform-specific host/IDNA interpretation. Java's
IDN API specifies the older RFC 3490 conversion; the WHATWG URL algorithm applies
its own domain processing and numeric-host rules. The literal serialization above
matches WHATWG IPv6 serialization, including hexadecimal IPv4-mapped output.
See [Java IDN](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/IDN.html),
[WHATWG host parsing](https://url.spec.whatwg.org/#host-parsing) and
[WHATWG IPv6 serialization](https://url.spec.whatwg.org/#concept-ipv6-serializer).

`control-v1.origins.fixtures.json` is shared unchanged by Java and Workers. It
contains explicit adversarial cases and deterministic IPv6 cases generated with
an independent WHATWG serializer. Every accepted vector must retain its exact
WHATWG origin bytes and be a usable JDK URI host. The tests also apply the same
profile through WebSocket and assisted-join validation.
Existing cryptographic golden fixture files and signed bytes remain unchanged.
