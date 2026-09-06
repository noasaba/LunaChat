# Versioning and compatibility

The independently managed versions are:

| Surface | Current | Rule |
| --- | --- | --- |
| Product/plugins | `4.0.21-SNAPSHOT` | SemVer; Paper/Velocity released together |
| Public Integration API | `1.0.0-SNAPSHOT` | SemVer and binary compatibility within a major |
| LCN wire | `6` | exact match; mixed incompatible versions fail closed |
| Velocity network config | `2` | sequential, line-preserving migration; reject future version |
| Velocity channel data | `2` | machine-managed state migration; reject future schema |
| Velocity membership data | `1` (`LCM1`) | binary machine state; fail closed on corruption |
| Paper config/data | `1` | independent from Velocity config and data schemas |

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
