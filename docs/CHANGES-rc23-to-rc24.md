# AccA rc23 to rc24: detailed notes

The full record behind the condensed 2.0.1-rc24 entry in CHANGELOG.md.

## 2.0.1-rc24 (229)

Pairs with ACC v2025.5.18-6.5.1-rc25. Install both. Bundles AMPS v7.3.3, byte-identical to the module's copy.

Fixed
- With ACC rc25, clearing a voltage limit now releases the hardware cap after a reboot or clean initialization instead of only clearing the value shown by AccA.
- The app said "Idle" when it had measured nothing at all. A plugged phone whose daemon returned no class, no status and no current printed Idle with the same confidence as one measured at 3 mA, and an unrecognised class landed there too. Idle is now a measurement; the absence of one reads "Unknown".
- An unreadable current is no longer a reading. The dashboard received `NaN` for a current ACC could not read or could not unit-match, and because every comparison against `NaN` is false it fell through to the Idle arm.
- A measured current now outranks the class and the kernel status in both directions. Previously only the negative half of that rule existed, so a phone taking +1000 mA could still print Idle when ACC and the kernel disagreed.
- Live capture called a negotiated 9 V "FAST charging is WORKING" with no wattage behind it. A high-voltage contract says the adapter agreed, not that power is flowing, which is the collapsed-supply case exactly. With nothing measured it now says the contract exists and the rate is unproven, and it requires the battery to actually be taking charge before it claims speed.
- The charger row printed `0.00 A` and `0.000 W` for values ACC could not read, on the row whose whole job is to explain a plugged phone that is not charging.
- The charger row was capped at two lines, so the sentence naming why charging is slow was cut mid-word on a OnePlus 8 Pro. It now has room for the longest wording.
- A failed config read came back as an empty string, which the parser turned into the default config, so a phone with a stopped daemon displayed 70/80 and a full temperature set as though they were being enforced. Both reads now require a `pause_capacity=` line and throw otherwise.
- ACC explains on stdout when it corrects a temperature or capacity it cannot take literally, and every caller was dropping that output, so a corrected threshold looked like the app had ignored the entry. The explanation is kept and shown once, for the apply that produced it.
- A scheduled apply joined its commands with `;`, so the last one decided what the shell reported and an earlier refusal was erased. Each command now leaves its own marker and the results are reported next time the app opens.
- Charge-once could not be stopped short of reaching the target or unplugging, and its confirmation accepted a leftover marker file from an earlier run as proof that one was running. The dialog now offers `acc -f 0`, takes a live daemon as the only positive proof, and recognises `status=Full`, which is how an aged pack finishes below the requested percentage.
- A failed read-back after a failed apply fell through to the default config, answering "what is ACC enforcing?" with "what ACC would enforce out of the box".

Added
- A `voltage_limit` reason arm, for ACC's new explanation that a pack is sitting at or above `max_charging_voltage`. Without it those phones would have shown the class and no reason at all.
