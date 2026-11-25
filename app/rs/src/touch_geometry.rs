// SPDX-License-Identifier: MPL-2.0

/// Convert a SurfaceView pixel coordinate to the fixed guest input axis.
pub fn axis(position: f32, view_extent: i32, device_extent: i32) -> i32 {
    if view_extent <= 0 || device_extent <= 0 || !position.is_finite() {
        return 0;
    }
    let fraction = (position as f64 / view_extent as f64).clamp(0.0, 1.0);
    (fraction * device_extent as f64).round() as i32
}

#[cfg(test)]
mod tests {
    use super::axis;

    #[test]
    fn resize_preserves_relative_position() {
        assert_eq!(axis(300.0, 600, 1200), 600);
        assert_eq!(axis(300.0, 1200, 600), 150);
        assert_eq!(axis(192.0, 800, 800), 192);
    }

    #[test]
    fn coordinates_stay_inside_advertised_range() {
        assert_eq!(axis(-4.0, 300, 600), 0);
        assert_eq!(axis(304.0, 300, 600), 600);
        assert_eq!(axis(f32::NAN, 300, 600), 0);
    }
}
