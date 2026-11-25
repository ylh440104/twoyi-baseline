// SPDX-License-Identifier: MPL-2.0

pub const EVENT_BYTES: usize = 24;

/// Encode an event for the legacy little-endian ARM64 guest.
pub fn encode_event(seconds: i64, microseconds: i64, kind: u16, code: u16, value: i32) -> [u8; EVENT_BYTES] {
    let mut frame = [0; EVENT_BYTES];
    frame[..8].copy_from_slice(&seconds.to_le_bytes());
    frame[8..16].copy_from_slice(&microseconds.to_le_bytes());
    frame[16..18].copy_from_slice(&kind.to_le_bytes());
    frame[18..20].copy_from_slice(&code.to_le_bytes());
    frame[20..].copy_from_slice(&value.to_le_bytes());
    frame
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arm64_touch_release_layout() {
        assert_eq!(encode_event(0x0102_0304_0506_0708, 0x090a, 3, 0x39, -1), [
            8, 7, 6, 5, 4, 3, 2, 1,
            10, 9, 0, 0, 0, 0, 0, 0,
            3, 0, 0x39, 0, 0xff, 0xff, 0xff, 0xff,
        ]);
    }

    #[test]
    fn synchronization_frame_with_zero_timestamp() {
        assert_eq!(encode_event(0, 0, 0, 0, 0), [0; EVENT_BYTES]);
    }
}
