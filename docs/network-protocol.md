# LCN1 network protocol

LCN1 is an internal LunaChat protocol, independent of LunaBridge protocol v2.
Wire version 1 runs on `lunachat:network_v1` and has separate logical and secure
frame identities.

Each secure frame authenticates protocol, session UUID, startup epoch,
sequence, fresh frame UUID, optional logical UUID, frame type, timestamps, and
payload with AES-256-GCM. A SHA-256-derived key is made from a minimum 32-byte
shared secret. Every encryption uses a random 96-bit nonce. Retry preserves the
logical UUID but creates a new frame UUID, sequence and nonce.

An authenticated `HELLO(nodeId)` starts/replaces a backend session. Velocity
accepts it only from a `ServerConnection` whose registered server name equals
the node ID, then returns `READY`. All later frames must match that session and
epoch. Paper sends a bounded channel `STATE` proposal after READY. Velocity
durably applies valid proposals before exposing them through its channel API.

`MESSAGE` is ACKed by logical ID. Velocity deduplicates before observer dispatch
and cross-backend fan-out. Paper runs inbound content through its normal final
message boundary and ACKs the final model. Loss of an ACK causes a newly
encrypted retry; the receiver does not render or notify twice. An authenticated
exact replay is discarded without disconnecting the session. Tamper, wrong
secret, unknown protocol, node/session mismatch, malformed payload, and stale
time windows close that backend session (fail closed).

All replay windows, receipts, external pending stages, and per-backend outboxes
are bounded. Entries expire. No offline queue is unbounded. Plugin messaging
uses an online player as carrier; a cold/empty backend retains only its bounded
Velocity outbox until a player creates a backend connection and handshake.
This transport does not promise immediate delivery to a backend with no active
server connection.

Velocity marks the registered plugin-message identifier handled before checking
the source, then accepts only backend `ServerConnection` sources. This prevents
the proxy from forwarding client-origin spoof messages.
