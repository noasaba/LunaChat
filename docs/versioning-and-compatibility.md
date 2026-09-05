# Versioning and compatibility

The independently managed versions are:

| Surface | Current | Rule |
| --- | --- | --- |
| Product/plugins | `4.0.12-SNAPSHOT` | SemVer; Paper/Velocity released together |
| Public Integration API | `1.0.0-SNAPSHOT` | SemVer and binary compatibility within a major |
| LCN wire | `4` | exact match; mixed incompatible versions fail closed |
| Config schema | `1` | backup/idempotent migration; reject future schema |
| Channel data schema | `1` | backup/idempotent migration; reject future schema |

The API artifact is independently compilable and has no platform dependencies.
Before releasing an API minor/patch, CI must compare its public signatures
against the latest release (japicmp or equivalent) and run
`lunachat-api-testkit` against standalone and network authorities. Removing or
changing a public method/type requires a new API major. Additive enum values
must be treated as a compatibility-sensitive change and documented.

Plugin metadata and Paper artifact version come from Maven filtering. Do not
hand-edit a second Paper version. LCN1's magic (`LCN1`) and wire number are not
shared with LunaBridge. A wire break increments the number and documents an
upgrade order; silent downgrade is prohibited.

Legacy LunaChat public packages and existing Google IME/external APIs are
retained. Migration to the Integration API is opt-in and additive.
