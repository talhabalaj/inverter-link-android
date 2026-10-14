# Protocol identification after QPI rejection

Android 0.1.2 adds **read-only identification**, not support for another inverter's measurements or settings.

The tested SolarMax Orion 4 kW / 24 V uses PI30. A reported MAXPOWER PV Tech 9000 installation reaches the logger but rejects `QPI`. The manufacturer's [PV Tech 9000 product page](https://maxpower.com.pk/product/pv-tech-9000/) identifies it as 6 kW / 48 V and lists communication interfaces, but does not establish its serial command dialect. The tentative name “PV9000” previously led to research into SolarMax ONYX; that is a different product and its manual is not evidence for this MAXPOWER unit.

## What 0.1.2 does

1. Perform the existing logger handshake and PI30 `QPI` query.
2. Only after a correctly framed, checksum-valid `NAK` to that query, try up to two fixed identifier formats on the same pinned logger route:

| Request | Framing | Specification |
| --- | --- | --- |
| `^P005PI` | CRC16-XMODEM with the documented byte escaping, then CR | PI18 identifier format |
| `^P003PI` | CR, no request CRC | PI17 identifier format |

3. Check the reply's route, transaction, framing, ASCII, checksum and declared length. A valid identifier is `^D005` plus two decimal ID digits, CRC and CR. The app reports the ID it receives without assigning that model a monitoring or settings profile.
4. Stop after a valid ID. A timeout or explicit `NAK` permits the next fixed identifier probe. Invalid checksums, malformed replies and route errors stop probing. A `NOA` or `ERCRC` to the original `QPI` does not trigger these probes.
5. Keep the connection unavailable for monitoring/settings and show a copyable report containing only query names, protocol ID or failure summaries. It contains no logger identity, serial number, IP address, account token or raw telemetry.

Every probe is a protocol-ID read. No ratings, status, register sweep, setting change or firmware operation is performed on the unverified device. Identifying PI17/PI18 does not establish the model-specific scaling, field layout or 48 V write limits.

## Evidence

The framing comes from original manufacturer protocol documents retained in a public mirror. Their revisions have not been matched to the friend's installation:

- [PI17 Infini-Solar WP/TWIN specification, 20220921](https://github.com/jblance/mpp-solar/blob/master/docs/protocols/PI17%20-%20Infini-Solar%20WP%20LV6K_3P%2010-15K%20and%20TWIN%20Series%20protocol%2020220921.pdf)
- [PI18 InfiniSolar-V specification, 20170926](https://github.com/jblance/mpp-solar/blob/master/docs/protocols/PI18_InfiniSolar-V-protocol-20170926.pdf)
- [MPP Solar's monitoring-app downloads](https://www.mppsolar.com/v3/download/) list Energy Mate for several inverter families; using that app does not establish PI30 compatibility.

Loopback tests reproduce QPI rejection, validate both identifier paths and verify that unsupported devices cannot send settings. Tests also cover timeouts, wrong lengths and checksum corruption. Real MAXPOWER identification remains pending.

To help establish support, install 0.1.2, reconnect, press **Copy** in the Connection dialog, and share the report plus the exact inverter model/firmware. Do not publish serials, Wi-Fi passwords or Energy Mate account credentials. Read the [security guide](../SECURITY.md).
