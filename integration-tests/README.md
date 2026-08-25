# Process integration tests

`mctest-smoke.sh` performs the disposable Paper 26.2 startup test described in
the supplied mctest instructions and always purges the environment. It requires
Docker/OrbStack to be running.

The network release gate uses mctest with `--velocity --paper 2`, places
`LunaChat-Velocity.jar` in the generated Velocity plugin directory, configures
both Paper nodes as `network_edge` with the generated shared secret, and then
restarts all three processes. The manual assertions are:

1. Velocity reports plugin `lunachat` 4.0 and both Papers report LunaChat 4.0.
2. Both edges reach `AUTHORITY_CONNECTED` after a player carrier connects.
3. A Paper-A channel message is rendered once on A and once on B.
4. Drop only its ACK and confirm retry has a new frame ID but B does not render twice.
5. Restart Velocity and confirm HELLO/READY reconnect and local Paper chat remains usable during the outage.
6. Publish one Discord-origin identity twice; the second result is `DUPLICATE` with the same logical UUID and no second display/observer event.
7. Tamper a frame and use a wrong secret; both are rejected without local-chat failure.
8. Move carriers between servers and repeat routing.

The current mctest CLI installs `--plugin` artifacts only into Paper, so the
Velocity-jar/config steps cannot yet be expressed through its stable CLI. Do
not edit `.mctest` files as product source; treat them only as disposable test
configuration. CI should automate this gate when mctest gains a Velocity plugin
option. The 2026-08-25 local attempt resolved Paper 26.2 build 117 but could not
start because the OrbStack Docker socket was absent.
