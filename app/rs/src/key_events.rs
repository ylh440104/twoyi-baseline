// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// Android KeyEvent -> Linux input-event-codes.h (guest Generic.kl mapping).
pub const KEY_MAP: &[(i32, i32)] = &[
    (3, 102),  // HOME
    (4, 158),  // BACK
    (24, 115), // VOLUME_UP
    (25, 114), // VOLUME_DOWN
    (82, 139), // MENU
];

/// A complete click: key down + SYN_REPORT, key up + SYN_REPORT.
/// Unknown keys must not silently turn into BACK.
pub fn click(android_code: i32) -> Option<[(i32, i32, i32); 4]> {
    let (_, linux_code) = KEY_MAP.iter().find(|(code, _)| *code == android_code)?;
    Some([(1, *linux_code, 1), (0, 0, 0), (1, *linux_code, 0), (0, 0, 0)])
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn navigation_keys_are_distinct_and_release_is_synchronized() {
        assert_eq!(click(4), Some([(1,158,1),(0,0,0),(1,158,0),(0,0,0)]));
        assert_eq!(click(3).unwrap()[0], (1,102,1));
        assert_eq!(click(82).unwrap()[0], (1,139,1));
        for &(android, linux) in KEY_MAP {
            assert_eq!(click(android), Some([(1,linux,1),(0,0,0),(1,linux,0),(0,0,0)]));
        }
    }
    #[test]
    fn unsupported_keys_produce_no_events() {
        assert_eq!(click(-1), None);
        assert_eq!(click(0), None);
        assert_eq!(click(9999), None);
    }
}
